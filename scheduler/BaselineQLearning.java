package org.cloudsimplus.myproject;

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

import java.util.*;

public class BaselineQLearning {

    private static final int NUM_VMS  = 5;
    private static final int VM_MIPS  = 1000;
    private static final int VM_PES   = 1;

    public static void main(String[] args) {

        // =====================================================================
        // 1. Create simulation
        // =====================================================================
        CloudSimPlus simulation = new CloudSimPlus();

        // =====================================================================
        // 2. Create Host  (8 PEs × 1000 MIPS each)
        // =====================================================================
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            peList.add(new PeSimple(1000));
        }

        Host host = new HostSimple(
            16384,      // RAM (MB)
            10000,      // Bandwidth (Mbps)
            1000000,    // Storage (MB)
            peList
        );

        new DatacenterSimple(simulation, List.of(host));

        // =====================================================================
        // 3. Create Broker
        // =====================================================================
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);

        // =====================================================================
        // 4. Create VMs
        // =====================================================================
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < NUM_VMS; i++) {
            Vm vm = new VmSimple(VM_MIPS, VM_PES)
                .setRam(1024)
                .setBw(1000)
                .setSize(10000)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vmList.add(vm);
        }
        broker.submitVmList(vmList);

        // =====================================================================
        // 5. Load cloudlets from Google trace
        // =====================================================================
        List<Cloudlet> cloudletList = GoogleTraceLoader.loadCloudlets();

        // =====================================================================
        // 6. Compute VM capacity for the Q-learning state encoder
        //
        //    We use the "fair-share" capacity:
        //        totalLoad / numVms
        //    so that Utilisation = 1.0 means this VM carries exactly its
        //    equal portion of the total work.
        //
        //    This maps directly to the paper's Ri = Ui / Ci  (eq. 1) where
        //    Ci is the total resources allocated to VM i.
        // =====================================================================
        long totalLoad = cloudletList.stream()
            .mapToLong(Cloudlet::getLength)
            .sum();

        long fairShareCapacity = (totalLoad / NUM_VMS) + 1; // +1 avoids division by zero edge case
        long[] vmCapacities = new long[NUM_VMS];
        Arrays.fill(vmCapacities, fairShareCapacity);

        System.out.printf("Total workload : %,d MI%n", totalLoad);
        System.out.printf("Fair-share cap : %,d MI per VM%n%n", fairShareCapacity);

        // =====================================================================
        // 7. Train Q-learning scheduler
        //
        //    The scheduler runs TRAINING_EPISODES offline passes over the
        //    cloudlet list, learning which VM state leads to the best reward.
        //    After training, ε is set to 0 and the greedy policy is used.
        // =====================================================================
        QlearningScheduler scheduler = new QlearningScheduler(NUM_VMS);
        scheduler.train(cloudletList, vmCapacities);

        // =====================================================================
        // 8. Apply trained policy to get final Cloudlet → VM assignments
        //
        //    IMPORTANT: bindings must be set BEFORE submitCloudletList(),
        //    otherwise the broker ignores them.
        // =====================================================================
        Map<Cloudlet, Integer> assignments =
            scheduler.schedule(cloudletList, vmCapacities);

        // Track actual loads for the post-schedule report
        double[] scheduledLoads = new double[NUM_VMS];

        for (Map.Entry<Cloudlet, Integer> entry : assignments.entrySet()) {
            Cloudlet cloudlet = entry.getKey();
            int      vmIndex  = entry.getValue();

            broker.bindCloudletToVm(cloudlet, vmList.get(vmIndex)); // Must be before submit
            scheduledLoads[vmIndex] += cloudlet.getLength();
        }

        // Submit cloudlets only AFTER all bindings are configured
        broker.submitCloudletList(cloudletList);

        // =====================================================================
        // 9. Run simulation
        // =====================================================================
        simulation.start();

        // =====================================================================
        // 10. Results
        // =====================================================================
        List<Cloudlet> finished = broker.getCloudletFinishedList();

        System.out.println("\n===== Q-Learning Baseline Scheduler — Results =====");

        new CloudletsTableBuilder(finished).build();

        MetricsCollection.printMetrics(finished, vmList.size());

        System.out.println("\nFinished Cloudlets: " + finished.size());

        // Print first 20 cloudlet→VM mappings (with bounds check)
        int printLimit = Math.min(20, finished.size());
        for (int i = 0; i < printLimit; i++) {
            Cloudlet c = finished.get(i);
            System.out.printf("Cloudlet %3d → VM %d%n", c.getId(), c.getVm().getId());
        }
    }
}
