package org.cloudsimplus.myproject;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Computes and prints performance metrics for a finished cloudlet list.
 *
 * IMPORTANT — fairness across schedulers:
 * Every metric here is derived only from what actually happened in the
 * simulation (each cloudlet's real assigned Vm, real start/finish times).
 * Nothing is read from scheduler-internal state (e.g. ECAScheduler's
 * Decision/State objects, or its scheduling-order-based carbon slot).
 * That's deliberate: this class is called after Round Robin, Energy-Aware,
 * plain Q-learning, and ECA alike, and the numbers must mean the same thing
 * regardless of which scheduler produced the assignment. A carbon-aware
 * scheduler should show up as a *better* number here, not a differently
 * computed one.
 */
public class MetricsCollection {

    private static final double MAX_POWER_WATTS = 250.0;
    private static final double STATIC_POWER_PERCENT = 0.70;
    //private static final int DEFAULT_VM_PES = 1;

    public static void printMetrics(List<Cloudlet> cloudlets, int vmCount) {

        System.out.println("\n===== PERFORMANCE METRICS =====");

        if (cloudlets.isEmpty()) {
            System.out.println("No finished cloudlets — nothing to report.");
            return;
        }

        double earliestStart = earliestStart(cloudlets);
        double makespan = makespan(cloudlets);

        double avgExecutionTime =
            cloudlets.stream()
                .mapToDouble(MetricsCollection::executionTime)
                .average()
                .orElse(0.0);

        double throughput =
            makespan > 0.0
                ? cloudlets.size() / makespan
                : 0.0;

        double utilization =
            (makespan > 0.0 && vmCount > 0)
                ? cloudlets.stream()
                .mapToDouble(MetricsCollection::executionTime)
                .sum() / (makespan * vmCount)
                : 0.0;

        double totalEnergyKWh = 0.0;
        double totalCarbonGrams = 0.0;

        for (Cloudlet cloudlet : cloudlets) {

            double energyKWh = cloudletEnergyKWh(cloudlet);

            double carbonIntensity =
                carbonIntensityForCloudlet(cloudlet, earliestStart, makespan);

            totalEnergyKWh += energyKWh;
            totalCarbonGrams += energyKWh * carbonIntensity;
        }

        System.out.printf("Makespan: %.2f seconds%n", makespan);
        System.out.printf("Average Execution Time: %.2f seconds%n", avgExecutionTime);
        System.out.printf("Throughput: %.4f cloudlets/sec%n", throughput);
        System.out.printf("Average VM Utilization: %.2f%%%n", utilization * 100);
        System.out.printf("Total Energy: %.6f kWh%n", totalEnergyKWh);
        System.out.printf("Total Carbon: %.6f gCO2%n", totalCarbonGrams);

        double carbonPerKWh =
            totalEnergyKWh > 0.0
                ? totalCarbonGrams / totalEnergyKWh
                : 0.0;
        System.out.printf(
            "Carbon Intensity of Consumed Energy: %.2f gCO2/kWh%n",
            carbonPerKWh
        );
    }

    // ─── Makespan / execution time / throughput ────────────────────────────

    private static double makespan(List<Cloudlet> cloudlets) {
        return cloudlets.stream()
            .mapToDouble(Cloudlet::getFinishTime)
            .max()
            .orElse(0.0);
    }

    private static double earliestStart(List<Cloudlet> cloudlets) {
        return cloudlets.stream()
            .mapToDouble(Cloudlet::getStartTime)
            .min()
            .orElse(0.0);
    }

    private static double executionTime(Cloudlet cloudlet) {
        double duration = cloudlet.getFinishTime() - cloudlet.getStartTime();
        return Math.max(0.0, duration);
    }

    // ─── Energy ──────────────────────────────────────────────────────────

    /**
     * Energy drawn by a single cloudlet, based on the Vm it was ACTUALLY
     * run on (cloudlet.getVm()), not any scheduler-predicted Vm. Mirrors
     * the power model lookup used in ECAScheduler so both stay consistent.
     */
    private static double cloudletEnergyKWh(Cloudlet cloudlet) {

        double actualCpuTime = executionTime(cloudlet);

        /**if (Double.isNaN(actualCpuTime) || actualCpuTime <= 0.0) {
            actualCpuTime = executionTime(cloudlet);
        }**/

        if (actualCpuTime <= 0.0) {
            return 0.0;
        }

        Vm vm = cloudlet.getVm();

// All VMs in this project have exactly 1 PE.
// A cloudlet occupies its assigned VM while executing,
// so we assume full utilization during execution.
        double utilization = 1.0;

        double watts = hostPowerWatts(vm, utilization);

        return watts * actualCpuTime / 3_600_000.0;
    }

    /**
     * Reflection is used here (not for lack of a typed API, but because
     * different CloudSim Plus builds vary in whether/how a PowerModel is
     * attached to a Host). Falls back to a static+dynamic linear model
     * matching the one ECAScheduler attaches, so results stay identical
     * whether or not a PowerModel class is present at runtime.
     */
    private static double hostPowerWatts(Vm vm, double utilization) {

        try {
            Host host = vm.getHost();

            Object powerModel = callNoArg(host, "getPowerModel");

            Object watts = call(
                powerModel,
                "getPower",
                new Class<?>[] { double.class },
                utilization
            );

            if (watts instanceof Number) {
                return ((Number) watts).doubleValue();
            }
        } catch (Exception ignored) {
            // Fall through to the default linear model below.
        }

        return MAX_POWER_WATTS
            * (STATIC_POWER_PERCENT
            + (1.0 - STATIC_POWER_PERCENT) * utilization);
    }

    // ─── Carbon ──────────────────────────────────────────────────────────

    /**
     * Carbon intensity (gCO2/kWh) applicable to a cloudlet, based on:
     *   - the Vm it actually ran on (cloudlet.getVm().getId()), and
     *   - WHEN it actually ran, proportionally mapped onto the carbon
     *     profile's slot range.
     *
     * This is deliberately independent of any scheduler's internal notion
     * of "carbon slot". ECAScheduler picks a slot from cloudlet submission
     * order (carbonSlotForDecision), which is only meaningful to ECA's own
     * bookkeeping. For a fair comparison across schedulers we instead ask:
     * "given the real wall-clock time this cloudlet executed, which slot
     * of the day was that?" — a question that's answerable identically for
     * Round Robin, Energy-Aware, plain Q-learning, and ECA alike.
     */
    private static double carbonIntensityForCloudlet(
        Cloudlet cloudlet,
        double earliestStart,
        double makespan
    ) {

        Vm vm = cloudlet.getVm();
        int vmIndex = (int) vm.getId();

        int slotCount = Math.max(1, CarbonIntensityLoader.slotCount(vmIndex));

        double duration = makespan - earliestStart;

        double midpoint =
            (cloudlet.getStartTime() + cloudlet.getFinishTime()) / 2.0;

        double fraction =
            duration > 0.0
                ? Math.max(0.0, Math.min(1.0, (midpoint - earliestStart) / duration))
                : 0.0;

        int slot = (int) Math.floor(fraction * slotCount);
        slot = Math.min(slotCount - 1, Math.max(0, slot));

        return CarbonIntensityLoader.getCarbonIntensity(vmIndex, slot);
    }

    // ─── Reflection helpers ──────────────────────────────────────────────

    private static Object callNoArg(Object target, String methodName) {
        return call(target, methodName, new Class<?>[0]);
    }

    private static Object call(
        Object target,
        String methodName,
        Class<?>[] parameterTypes,
        Object... args
    ) {

        if (target == null) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }
}
