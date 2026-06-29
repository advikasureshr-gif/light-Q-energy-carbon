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
import java.util.List;

public class RoundRobin {

    public static void main(String[] args) {

        // 1. Create simulation
        CloudSimPlus simulation = new CloudSimPlus();

        // 2. Create Host
        List<Pe> peList = new ArrayList<>();

        for(int i = 0; i < 8; i++) {
            peList.add(new PeSimple(1000));
        }

        Host host = new HostSimple(
            16384,      // RAM
            10000,      // BW
            1000000,    // Storage
            peList
        );

        DatacenterSimple datacenter =
            new DatacenterSimple(simulation, List.of(host));

        // 3. Create Broker
        DatacenterBrokerSimple broker =
            new DatacenterBrokerSimple(simulation);

        // 4. Create Multiple VMs
        List<Vm> vmList = new ArrayList<>();

        for(int i = 0; i < 5; i++) {

            Vm vm = new VmSimple(1000, 1)
                .setRam(1024)
                .setBw(1000)
                .setSize(10000)
                .setCloudletScheduler(
                    new CloudletSchedulerSpaceShared());

            vmList.add(vm);
        }

        broker.submitVmList(vmList);

        // 5. Load Google Trace cloudlets
        List<Cloudlet> cloudletList =
            GoogleTraceLoader.loadCloudlets();

        broker.submitCloudletList(cloudletList);

        int vmIndex = 0;

        for (Cloudlet cloudlet : cloudletList) {

            broker.bindCloudletToVm(
                cloudlet,
                vmList.get(vmIndex));

            vmIndex = (vmIndex + 1) % vmList.size();
        }

        // 6. Run simulation
        simulation.start();

        // 7. Results
        System.out.println("===== Round Robin Results =====");

        new CloudletsTableBuilder(
            broker.getCloudletFinishedList())
            .build();
        MetricsCollection.printMetrics(
            broker.getCloudletFinishedList(),
            vmList.size()
        );

        System.out.println(
            "\nFinished Cloudlets: "
                + broker.getCloudletFinishedList().size());

        for (int i = 0; i < 20; i++) {
            Cloudlet c = broker.getCloudletFinishedList().get(i);

            System.out.println(
                "Cloudlet " + c.getId() +
                    " -> VM " + c.getVm().getId()
            );
        }
    }
}

