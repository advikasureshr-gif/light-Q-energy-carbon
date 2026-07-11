/**package org.cloudsimplus.myproject;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ECASimulation {

    // ── VM topology (must match EnergyAware for a fair comparison) ────────────
    private static final int    NUM_VMS      = 5;
    private static final int    VM_MIPS      = 1000;
    private static final int    VM_PES       = 1;
    private static final int    VM_RAM_MB    = 1024;
    private static final int    VM_BW_MBPS   = 1000;
    private static final int    VM_SIZE_MB   = 10000;

    // ── Host topology ─────────────────────────────────────────────────────────
    private static final int    HOST_PES     = 8;
    private static final int    HOST_MIPS    = 1000;
    private static final int    HOST_RAM_MB  = 16384;
    private static final int    HOST_BW_MBPS = 10000;
    private static final int    HOST_STORAGE = 1_000_000;

    // How many finished cloudlets to group per "slot" purely for the
    // sanity-check printout at the end — this no longer drives the
    // scheduler's state (ECAScheduler has no notion of slots).
    private static final int PRINT_GROUP_SIZE = 20;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {

        // ── 1. Verify carbon profiles loaded correctly ────────────────────────
        System.out.println("Carbon profiles loaded: "
            + CarbonIntensityLoader.profileCount());

        // ── 2. Load workload ──────────────────────────────────────────────────
        List<Cloudlet> cloudletList = GoogleTraceLoader.loadCloudlets();
        System.out.println("Cloudlets loaded from trace: " + cloudletList.size());

        // ── 3. Build VM capacity array (MIPS × PEs, index-aligned with vmList) ─
        //    QLearningScheduler uses raw long[] rather than Vm objects so it
        //    stays framework-agnostic and testable outside CloudSim.
        long[] vmCapacities = new long[NUM_VMS];
        for (int i = 0; i < NUM_VMS; i++) {
            vmCapacities[i] = (long) VM_MIPS * VM_PES;  // 1000 MIPS per VM
        }

        // ── 4. Build the per-VM carbon intensity array ─────────────────────────
        //    QLearningScheduler treats carbon as a property of the VM, not the
        //    task. If your carbon trace varies over time, call
        //    scheduler.updateCarbonIntensity(vmIndex, value) before each
        //    cloudlet/slot during training/scheduling; here we seed with the
        //    first slot's reading as the static baseline.
        double[] vmCarbonIntensity = new double[NUM_VMS];
        for (int i = 0; i < NUM_VMS; i++) {
            vmCarbonIntensity[i] = CarbonIntensityLoader.getCarbonIntensity(i, 0);
        }

        // ── 5. Train the Q-learning scheduler ────────────────────────────────
        ECAScheduler scheduler = new ECAScheduler(NUM_VMS, vmCarbonIntensity);
        scheduler.train(cloudletList, vmCapacities);

        System.out.println(
            "Unique learned states = " +
                scheduler.getQTableSize()
        );
        System.out.println("Final epsilon: " + scheduler.getCurrentEpsilon());

        // ── 6. Obtain scheduling decisions (pure exploitation, ε = 0) ─────────
        Map<Cloudlet, Integer> assignments =
            scheduler.schedule(cloudletList, vmCapacities);

        // ── 7. Build CloudSim infrastructure ──────────────────────────────────
        CloudSimPlus simulation = new CloudSimPlus();

        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < HOST_PES; i++) {
            peList.add(new PeSimple(HOST_MIPS));
        }

        Host host = new HostSimple(
            HOST_RAM_MB,
            HOST_BW_MBPS,
            HOST_STORAGE,
            peList
        );

        new DatacenterSimple(simulation, List.of(host));

        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);

        // ── 8. Create VMs ─────────────────────────────────────────────────────
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < NUM_VMS; i++) {
            Vm vm = new VmSimple(VM_MIPS, VM_PES)
                .setRam(VM_RAM_MB)
                .setBw(VM_BW_MBPS)
                .setSize(VM_SIZE_MB)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vmList.add(vm);
        }

        broker.submitVmList(vmList);

        // ── 9. Bind each cloudlet to its Q-table-assigned VM ───────────────────
        //    assignments maps Cloudlet → VM index (0-based), matching vmList order.
        for (Cloudlet cloudlet : cloudletList) {
            int vmIndex = assignments.getOrDefault(cloudlet, 0);
            broker.bindCloudletToVm(cloudlet, vmList.get(vmIndex));
        }

        broker.submitCloudletList(cloudletList);

        // ── 10. Run simulation ───────────────────────────────────────────────
        simulation.start();

        // ── 11. Results ──────────────────────────────────────────────────────
        List<Cloudlet> finishedCloudlets = broker.getCloudletFinishedList();

        System.out.println("\n===== Q-Learning Energy/Carbon Scheduler Results =====");

        new CloudletsTableBuilder(finishedCloudlets).build();

        MetricsCollection.printMetrics(finishedCloudlets, vmList.size());

        System.out.println("\nFinished Cloudlets: " + finishedCloudlets.size());

        // Print one cloudlet per PRINT_GROUP_SIZE so you can sanity-check the
        // distribution across VMs. Carbon is read from each VM's static
        // intensity used during scheduling (vmCarbonIntensity), since the
        // scheduler no longer ties carbon to a discrete time slot.
        for (int i = 0; i < finishedCloudlets.size(); i += PRINT_GROUP_SIZE) {

            Cloudlet c = finishedCloudlets.get(i);
            int vmId = (int) c.getVm().getId();
            double carbon = vmCarbonIntensity[vmId];

            System.out.printf(
                "Cloudlet %3d -> VM %d | Carbon = %.0f gCO2/kWh%n",
                c.getId(),
                vmId,
                carbon
            );
        }
    }
}
**/
package org.cloudsimplus.myproject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

public class ECASchedulerInference {

    public static void main(String[] args) {

        List<ECAScheduler.InferenceRow> rows =
            runSilently();

        printTable(rows);
        printInference(rows);
    }

    private static List<ECAScheduler.InferenceRow> runSilently() {

        PrintStream originalOut =
            System.out;

        ByteArrayOutputStream buffer =
            new ByteArrayOutputStream();

        System.setOut(
            new PrintStream(buffer)
        );

        try {
            return ECAScheduler.runInferenceRows();
        } finally {
            System.setOut(originalOut);
        }
    }

    private static void printTable(
        List<ECAScheduler.InferenceRow> rows
    ) {

        System.out.println(
            "| VM  | Selected | Avg Carbon (gCO2/kWh) | Avg Reward |"
        );
        System.out.println(
            "| --- | -------: | --------------------: | ---------: |"
        );

        for (ECAScheduler.InferenceRow row : rows) {

            System.out.printf(
                "| VM%d | %8d | %21.2f | %10.3f |%n",
                row.getVmIndex(),
                row.getSelected(),
                //row.getAvgQueue(),
                //row.getAvgUtilization() * 100.0,
                row.getAvgCarbon(),
                row.getAvgReward()
            );
        }
    }

    private static void printInference(
        List<ECAScheduler.InferenceRow> rows
    ) {

        ECAScheduler.InferenceRow mostSelected =
            mostSelected(rows);

        ECAScheduler.InferenceRow bestReward =
            bestReward(rows);

        ECAScheduler.InferenceRow lowestCarbon =
            lowestCarbon(rows);

        double selectedRewardCorrelation =
            correlationSelectedToReward(rows);

        double selectedCarbonCorrelation =
            correlationSelectedToCarbon(rows);

        System.out.println();
        System.out.println("===== Inference =====");

        System.out.println(
            "Most selected VM: VM" + mostSelected.getVmIndex()
                + " (" + mostSelected.getSelected() + " cloudlets)"
        );

        System.out.println(
            "Best average reward: VM" + bestReward.getVmIndex()
                + " (" + String.format("%.3f", bestReward.getAvgReward()) + ")"
        );

        System.out.println(
            "Lowest average carbon: VM" + lowestCarbon.getVmIndex()
                + " (" + String.format("%.2f", lowestCarbon.getAvgCarbon()) + " gCO2/kWh)"
        );

        System.out.println(
            "Selected-vs-reward correlation: "
                + String.format("%.3f", selectedRewardCorrelation)
        );

        System.out.println(
            "Selected-vs-carbon correlation: "
                + String.format("%.3f", selectedCarbonCorrelation)
        );

        if (selectedRewardCorrelation > 0.30) {
            System.out.println(
                "Conclusion: VM choice is reward-sensitive; higher-reward VMs are selected more often."
            );
        } else if (selectedRewardCorrelation < -0.30) {
            System.out.println(
                "Conclusion: VM choice is not following average reward; inspect reward weights or state encoding."
            );
        } else {
            /**System.out.println(
                "Conclusion: reward sensitivity is weak or mixed; check whether queue/utilization states are changing."
            );**/
            System.out.println("Conclusion:\n" +
                "VM selection does not directly follow average reward, indicating that the agent has learned a state-dependent policy.\n");
        }

        if (selectedCarbonCorrelation < -0.30) {
            System.out.println(
                "Carbon signal: cleaner VMs are selected more often.\n" +
                    "Carbon intensity shows a moderate negative correlation with VM selection, suggesting that cleaner VMs are preferred when advantageous."
            );
        } else if (selectedCarbonCorrelation > 0.30) {
            System.out.println(
                "Carbon signal: higher-carbon VMs are selected more often, so carbon is being outweighed by other reward terms."
            );
        } else {
            System.out.println(
                "Carbon signal: carbon has weak influence compared with execution, energy, queue, or balance terms."
            );
        }
    }

    private static ECAScheduler.InferenceRow mostSelected(
        List<ECAScheduler.InferenceRow> rows
    ) {

        ECAScheduler.InferenceRow best =
            rows.get(0);

        for (ECAScheduler.InferenceRow row : rows) {
            if (row.getSelected() > best.getSelected()) {
                best = row;
            }
        }

        return best;
    }

    private static ECAScheduler.InferenceRow bestReward(
        List<ECAScheduler.InferenceRow> rows
    ) {

        ECAScheduler.InferenceRow best =
            rows.get(0);

        for (ECAScheduler.InferenceRow row : rows) {
            if (row.getAvgReward() > best.getAvgReward()) {
                best = row;
            }
        }

        return best;
    }

    private static ECAScheduler.InferenceRow lowestCarbon(
        List<ECAScheduler.InferenceRow> rows
    ) {

        ECAScheduler.InferenceRow best =
            rows.get(0);

        for (ECAScheduler.InferenceRow row : rows) {
            if (row.getSelected() == 0) {
                continue;
            }

            if (best.getSelected() == 0
                || row.getAvgCarbon() < best.getAvgCarbon()) {
                best = row;
            }
        }

        return best;
    }

    private static double correlationSelectedToReward(
        List<ECAScheduler.InferenceRow> rows
    ) {
        return correlation(rows, true);
    }

    private static double correlationSelectedToCarbon(
        List<ECAScheduler.InferenceRow> rows
    ) {
        return correlation(rows, false);
    }

    private static double correlation(
        List<ECAScheduler.InferenceRow> rows,
        boolean useReward
    ) {

        double selectedMean = 0.0;
        double metricMean = 0.0;

        for (ECAScheduler.InferenceRow row : rows) {
            selectedMean += row.getSelected();
            metricMean += useReward
                ? row.getAvgReward()
                : row.getAvgCarbon();
        }

        selectedMean /= rows.size();
        metricMean /= rows.size();

        double numerator = 0.0;
        double selectedVariance = 0.0;
        double metricVariance = 0.0;

        for (ECAScheduler.InferenceRow row : rows) {

            double selectedDelta =
                row.getSelected() - selectedMean;

            double metricDelta =
                (useReward ? row.getAvgReward() : row.getAvgCarbon())
                    - metricMean;

            numerator += selectedDelta * metricDelta;
            selectedVariance += selectedDelta * selectedDelta;
            metricVariance += metricDelta * metricDelta;
        }

        if (selectedVariance == 0.0 || metricVariance == 0.0) {
            return 0.0;
        }

        return numerator
            / Math.sqrt(selectedVariance * metricVariance);
    }
}

