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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnergyAware {

    public static void main(String[] args) {

        // 1. Create simulation
        CloudSimPlus simulation = new CloudSimPlus();

        // 2. Create Host
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            peList.add(new PeSimple(1000));
        }

        Host host = new HostSimple(
            16384,      // RAM (MB)
            10000,      // BW (Mbps)
            1000000,    // Storage (MB)
            peList
        );

        DatacenterSimple datacenter =
            new DatacenterSimple(simulation, List.of(host));

        // 3. Create Broker
        DatacenterBrokerSimple broker =
            new DatacenterBrokerSimple(simulation);

        // 4. Create Multiple VMs
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Vm vm = new VmSimple(1000, 1)
                .setRam(1024)
                .setBw(1000)
                .setSize(10000)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vmList.add(vm);
        }

        broker.submitVmList(vmList);

        // 5. Load Google Trace cloudlets
        List<Cloudlet> cloudletList = GoogleTraceLoader.loadCloudlets();

        // FIX 1: Use Long instead of Integer — getLength() returns long
        Map<Vm, Long> vmLoad = new HashMap<>();
        for (Vm vm : vmList) {
            // FIX 2: Use 0L (long literal) with the correct Map type
            vmLoad.put(vm, 0L);
        }

        // FIX 3: Bind cloudlets to VMs BEFORE submitting to broker
        for (Cloudlet cloudlet : cloudletList) {
            Vm selectedVm = vmLoad.entrySet()
                .stream()
                .min(Map.Entry.comparingByValue())
                .get()
                .getKey();

            broker.bindCloudletToVm(cloudlet, selectedVm);

            // Accumulate workload per VM for energy-aware balancing
            vmLoad.put(
                selectedVm,
                vmLoad.get(selectedVm) + cloudlet.getLength()
            );
        }

        // Submit cloudlets only AFTER binding is done
        broker.submitCloudletList(cloudletList);

        // 6. Run simulation
        simulation.start();

        // 7. Results
        System.out.println("===== Energy Aware Results =====");

        new CloudletsTableBuilder(
            broker.getCloudletFinishedList())
            .build();

        MetricsCollection.printMetrics(
            broker.getCloudletFinishedList(),
            vmList.size()
        );

        List<Cloudlet> finishedCloudlets =
            broker.getCloudletFinishedList();

        System.out.println(
            "\nFinished Cloudlets: " + finishedCloudlets.size());

        // FIX 4: Guard against lists smaller than 20
        int printLimit = Math.min(20, finishedCloudlets.size());
        for (int i = 0; i < printLimit; i++) {
            Cloudlet c = finishedCloudlets.get(i);
            System.out.println(
                "Cloudlet " + c.getId() +
                    " -> VM " + c.getVm().getId()
            );
        }
    }
}
