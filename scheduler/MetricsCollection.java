package org.cloudsimplus.myproject;

import org.cloudsimplus.cloudlets.Cloudlet;

import java.util.List;

    public class MetricsCollection {

        public static void printMetrics(List<Cloudlet> cloudlets, int vmCount) {

            double makespan = cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0);

            double avgExecutionTime = cloudlets.stream()
                .mapToDouble(c -> c.getFinishTime() - c.getStartTime())
                .average()
                .orElse(0);

            double throughput = cloudlets.size() / makespan;

            double utilization =
                cloudlets.stream()
                    .mapToDouble(
                        c -> c.getFinishTime() - c.getStartTime()
                    )
                    .sum()
                    / (makespan * vmCount);


            System.out.println("\n===== PERFORMANCE METRICS =====");

            System.out.printf(
                "Makespan: %.2f seconds%n",
                makespan);

            System.out.printf(
                "Average Execution Time: %.2f seconds%n",
                avgExecutionTime);

            System.out.printf(
                "Throughput: %.4f cloudlets/sec%n",
                throughput);

            System.out.printf(
                "Average VM Utilization: %.2f%%%n",
                utilization * 100
            );

        }
    }


