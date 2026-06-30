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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ECAScheduler {

    private static final int VM_COUNT = 5;
    private static final int HOST_PES = 8;
    private static final int TRAINING_EPISODES = 50;

    private static final double ALPHA = 0.15;
    private static final double GAMMA = 0.90;
    private static final double EPSILON_START = 1.0;
    private static final double EPSILON_MIN = 0.05;
    private static final double EPSILON_DECAY = 0.96;

    private static final long HOST_MIPS = 1000;
    private static final long HOST_RAM = 16384;
    private static final long HOST_BW = 10000;
    private static final long HOST_STORAGE = 1000000;

    private static final long VM_MIPS = 1000;
    private static final int VM_PES = 1;
    private static final long VM_RAM = 1024;
    private static final long VM_BW = 1000;
    private static final long VM_SIZE = 10000;

    private static final double MAX_POWER_WATTS = 250.0;
    private static final double STATIC_POWER_PERCENT = 0.70;

    public static void main(String[] args) {

        QLearningAgent agent =
            new QLearningAgent(
                VM_COUNT,
                ALPHA,
                GAMMA,
                EPSILON_START,
                EPSILON_MIN,
                EPSILON_DECAY
            );

        for (int episode = 1; episode <= TRAINING_EPISODES; episode++) {

            EpisodeResult result =
                runEpisode(agent, true, false);

            agent.decayEpsilon();

            System.out.println(
                "Episode " + episode +
                    " | Reward = " + String.format("%.3f", result.totalReward) +
                    " | Finished = " + result.finishedCloudlets +
                    " | Epsilon = " + String.format("%.4f", agent.getEpsilon())
            );
        }

        agent.setEpsilon(0.0);

        EpisodeResult finalResult =
            runEpisode(agent, false, true);

        System.out.println("\n===== ECA Scheduler Final Results =====");
        System.out.println("Finished Cloudlets: " + finalResult.finishedCloudlets);
        System.out.println("Total Reward: " + String.format("%.3f", finalResult.totalReward));
        System.out.println("Total Energy: " + String.format("%.6f", finalResult.totalEnergyKWh) + " kWh");
        System.out.println("Total Carbon: " + String.format("%.6f", finalResult.totalCarbonGrams) + " gCO2");
        System.out.println("VM Selections: " + finalResult.vmSelections);
        System.out.println("Q-table States: " + agent.stateCount());
    }

    public static List<InferenceRow> runInferenceRows() {
        return runInferenceRows(TRAINING_EPISODES);
    }

    public static List<InferenceRow> runInferenceRows(int trainingEpisodes) {

        QLearningAgent agent =
            new QLearningAgent(
                VM_COUNT,
                ALPHA,
                GAMMA,
                EPSILON_START,
                EPSILON_MIN,
                EPSILON_DECAY
            );

        for (int episode = 1; episode <= trainingEpisodes; episode++) {
            runEpisode(agent, true, false);
            agent.decayEpsilon();
        }

        agent.setEpsilon(0.0);

        EpisodeResult result =
            runEpisode(agent, false, false);

        return result.inferenceRows;
    }

    private static EpisodeResult runEpisode(
        QLearningAgent agent,
        boolean training,
        boolean printResults
    ) {

        CloudSimPlus simulation = new CloudSimPlus();

        Host host = createHost();

        DatacenterSimple datacenter =
            new DatacenterSimple(simulation, List.of(host));

        DatacenterBrokerSimple broker =
            new DatacenterBrokerSimple(simulation);

        List<Vm> vmList = createVms();

        broker.submitVmList(vmList);

        List<Cloudlet> cloudletList =
            GoogleTraceLoader.loadCloudlets();

        broker.submitCloudletList(cloudletList);

        List<Decision> decisions =
            bindCloudletsUsingQlearning(
                broker,
                agent,
                cloudletList,
                vmList,
                training
            );

        simulation.start();

        List<Cloudlet> finishedCloudlets =
            broker.getCloudletFinishedList();

        EpisodeResult result =
            learnFromResults(
                agent,
                decisions,
                finishedCloudlets,
                training
            );

        if (printResults) {
            new CloudletsTableBuilder(finishedCloudlets).build();

            MetricsCollection.printMetrics(
                finishedCloudlets,
                vmList.size()
            );

            printFirstAssignments(finishedCloudlets, 20);
        }

        return result;
    }

    private static Host createHost() {

        List<Pe> peList = new ArrayList<>();

        for (int i = 0; i < HOST_PES; i++) {
            peList.add(new PeSimple(HOST_MIPS));
        }

        Host host =
            new HostSimple(
                HOST_RAM,
                HOST_BW,
                HOST_STORAGE,
                peList
            );

        attachPowerModelIfAvailable(host);

        return host;
    }

    private static List<Vm> createVms() {

        List<Vm> vmList = new ArrayList<>();

        for (int i = 0; i < VM_COUNT; i++) {

            Vm vm =
                new VmSimple(VM_MIPS, VM_PES)
                    .setRam(VM_RAM)
                    .setBw(VM_BW)
                    .setSize(VM_SIZE)
                    .setCloudletScheduler(
                        new CloudletSchedulerSpaceShared());

            vmList.add(vm);
        }

        return vmList;
    }

    private static List<Decision> bindCloudletsUsingQlearning(
        DatacenterBrokerSimple broker,
        QLearningAgent agent,
        List<Cloudlet> cloudletList,
        List<Vm> vmList,
        boolean training
    ) {

        List<Decision> decisions = new ArrayList<>();

        for (int i = 0; i < cloudletList.size(); i++) {

            Cloudlet cloudlet = cloudletList.get(i);

            int carbonSlot =
                carbonSlotForDecision(i, cloudletList.size());

            State state =
                State.from(cloudlet, vmList, carbonSlot);

            int selectedVmIndex =
                agent.chooseAction(state, training);

            Vm selectedVm =
                vmList.get(selectedVmIndex);

            broker.bindCloudletToVm(
                cloudlet,
                selectedVm
            );

            Decision decision =
                new Decision(
                    cloudlet,
                    selectedVm,
                    selectedVmIndex,
                    state,
                    carbonSlot,
                    CarbonIntensityLoader.getCarbonIntensity(
                        selectedVmIndex,
                        carbonSlot
                    ),
                    queueLength(selectedVm)
                );

            decisions.add(decision);
        }

        return decisions;
    }

    private static EpisodeResult learnFromResults(
        QLearningAgent agent,
        List<Decision> decisions,
        List<Cloudlet> finishedCloudlets,
        boolean training
    ) {

        EpisodeResult result = new EpisodeResult();

        result.finishedCloudlets = finishedCloudlets.size();

        for (Decision decision : decisions) {
            result.vmSelections.merge(
                decision.vmIndex,
                1,
                Integer::sum
            );
        }

        double avgCloudletsPerVm =
            decisions.isEmpty()
                ? 0.0
                : decisions.size() / (double) VM_COUNT;

        Map<Integer, InferenceAccumulator> inferenceMap =
            new HashMap<>();

        for (int vmIndex = 0; vmIndex < VM_COUNT; vmIndex++) {
            inferenceMap.put(
                vmIndex,
                new InferenceAccumulator(vmIndex)
            );
        }

        double episodeDuration =
            episodeDuration(finishedCloudlets);

        for (int i = 0; i < decisions.size(); i++) {

            Decision decision = decisions.get(i);

            double reward =
                reward(
                    decision,
                    result.vmSelections,
                    avgCloudletsPerVm
                );

            State nextState =
                i + 1 < decisions.size()
                    ? decisions.get(i + 1).state
                    : null;

            if (training) {
                agent.update(
                    decision.state,
                    decision.vmIndex,
                    reward,
                    nextState
                );
            }

            result.totalReward += reward;
            result.totalEnergyKWh += cloudletEnergyKWh(decision);
            result.totalCarbonGrams += cloudletCarbonGrams(decision);

            inferenceMap
                .get(decision.vmIndex)
                .add(
                    decision,
                    reward,
                    measuredBusyTime(decision),
                    episodeDuration
                );
        }

        for (int vmIndex = 0; vmIndex < VM_COUNT; vmIndex++) {
            result.inferenceRows.add(
                inferenceMap.get(vmIndex).toRow()
            );
        }

        return result;
    }

    private static double reward(
        Decision decision,
        Map<Integer, Integer> vmSelections,
        double avgCloudletsPerVm
    ) {

        double executionTime =
            positiveMetric(decision.cloudlet, "getActualCpuTime");

        double waitingTime =
            positiveMetric(decision.cloudlet, "getWaitingTime");

        double energyKWh =
            cloudletEnergyKWh(decision);

        double carbonGrams =
            energyKWh * decision.carbonIntensity;

        double executionScore =
            35.0 / (1.0 + executionTime);

        double waitingScore =
            25.0 / (1.0 + waitingTime);

        double energyScore =
            20.0 / (1.0 + energyKWh * 1000.0);

        double carbonScore =
            20.0 / (1.0 + carbonGrams);

        double queuePenalty =
            Math.max(0, decision.queueLengthAtSelection - 4) * 4.0;

        int selectedCount =
            vmSelections.getOrDefault(decision.vmIndex, 0);

        double balancePenalty =
            Math.max(0.0, selectedCount - avgCloudletsPerVm) * 0.15;

        return executionScore
            + waitingScore
            + energyScore
            + carbonScore
            - queuePenalty
            - balancePenalty;
    }

    private static int carbonSlotForDecision(
        int cloudletIndex,
        int cloudletCount
    ) {

        int slots =
            Math.max(1, CarbonIntensityLoader.slotCount(0));

        int cloudletsPerSlot =
            Math.max(1, cloudletCount / slots);

        return (cloudletIndex / cloudletsPerSlot) % slots;
    }

    private static double cloudletCarbonGrams(Decision decision) {
        return cloudletEnergyKWh(decision) * decision.carbonIntensity;
    }

    private static double cloudletEnergyKWh(Decision decision) {

        double actualCpuTime =
            positiveMetric(decision.cloudlet, "getActualCpuTime");

        if (actualCpuTime == 0.0) {
            actualCpuTime =
                positiveMetric(decision.cloudlet, "getTotalExecutionTime");
        }

        if (actualCpuTime == 0.0) {
            return 0.0;
        }

        double utilization =
            Math.min(
                1.0,
                cloudletPes(decision.cloudlet)
                    / (double) Math.max(1, VM_PES)
            );

        double watts =
            hostPowerWatts(decision.vm, utilization);

        return watts * actualCpuTime / 3600000.0;
    }

    private static double measuredBusyTime(Decision decision) {

        double actualCpuTime =
            positiveMetric(decision.cloudlet, "getActualCpuTime");

        if (actualCpuTime == 0.0) {
            actualCpuTime =
                positiveMetric(decision.cloudlet, "getTotalExecutionTime");
        }

        return actualCpuTime
            * cloudletPes(decision.cloudlet)
            / (double) Math.max(1, VM_PES);
    }

    private static double episodeDuration(List<Cloudlet> finishedCloudlets) {

        double firstStart = Double.MAX_VALUE;
        double lastFinish = 0.0;

        for (Cloudlet cloudlet : finishedCloudlets) {

            double start =
                positiveMetric(cloudlet, "getExecStartTime");

            double finish =
                positiveMetric(cloudlet, "getFinishTime");

            if (finish == 0.0) {
                finish =
                    start
                        + positiveMetric(
                        cloudlet,
                        "getActualCpuTime"
                    );
            }

            if (start > 0.0) {
                firstStart =
                    Math.min(firstStart, start);
            }

            lastFinish =
                Math.max(lastFinish, finish);
        }

        if (firstStart == Double.MAX_VALUE) {
            firstStart = 0.0;
        }

        return Math.max(1.0, lastFinish - firstStart);
    }

    private static double hostPowerWatts(Vm vm, double utilization) {

        Object host =
            callNoArg(vm, "getHost");

        Object powerModel =
            callNoArg(host, "getPowerModel");

        Object watts =
            call(
                powerModel,
                "getPower",
                new Class<?>[] { double.class },
                utilization
            );

        if (watts instanceof Number) {
            return ((Number) watts).doubleValue();
        }

        return MAX_POWER_WATTS
            * (STATIC_POWER_PERCENT
            + (1.0 - STATIC_POWER_PERCENT) * utilization);
    }

    private static int queueLength(Vm vm) {

        Object scheduler =
            callNoArg(vm, "getCloudletScheduler");

        return collectionSize(
            callNoArg(scheduler, "getCloudletExecList"))
            + collectionSize(
            callNoArg(scheduler, "getCloudletWaitingList"));
    }

    private static double vmUtilization(Vm vm) {

        Object value =
            callNoArg(vm, "getCpuPercentUtilization");

        if (!(value instanceof Number)) {
            return 0.0;
        }

        double utilization =
            ((Number) value).doubleValue();

        if (utilization > 1.0) {
            utilization = utilization / 100.0;
        }

        return Math.max(
            0.0,
            Math.min(1.0, utilization)
        );
    }

    private static int workloadCategory(Cloudlet cloudlet) {

        long length = cloudletLength(cloudlet);

        if (length <= 1200) {
            return 0;
        }

        if (length <= 2200) {
            return 1;
        }

        return 2;
    }

    private static int utilizationCategory(double utilization) {

        if (utilization < 0.20) {
            return 0;
        }

        if (utilization < 0.50) {
            return 1;
        }

        if (utilization < 0.80) {
            return 2;
        }

        return 3;
    }

    private static long cloudletLength(Cloudlet cloudlet) {

        Object value =
            callNoArg(cloudlet, "getLength");

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return 1000;
    }

    private static int cloudletPes(Cloudlet cloudlet) {

        Object value =
            callNoArg(cloudlet, "getNumberOfPes");

        if (!(value instanceof Number)) {
            value =
                callNoArg(cloudlet, "getPesNumber");
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return VM_PES;
    }

    private static int queueCategory(int queueLength) {

        if (queueLength == 0) {
            return 0;
        }

        if (queueLength <= 2) {
            return 1;
        }

        if (queueLength <= 5) {
            return 2;
        }

        return 3;
    }

    private static void attachPowerModelIfAvailable(Host host) {

        Object powerModel =
            createPowerModelIfAvailable();

        if (powerModel == null) {
            return;
        }

        for (Method method : host.getClass().getMethods()) {

            if (!method.getName().equals("setPowerModel")) {
                continue;
            }

            if (method.getParameterCount() != 1) {
                continue;
            }

            try {
                method.invoke(host, powerModel);
                return;
            } catch (Exception ignored) {
                // Try another compatible CloudSim Plus signature.
            }
        }
    }

    private static Object createPowerModelIfAvailable() {

        String[] classNames = {
            "org.cloudsimplus.power.models.PowerModelHostSimple",
            "org.cloudsimplus.power.models.PowerModelLinear"
        };

        for (String className : classNames) {

            try {
                Class<?> clazz =
                    Class.forName(className);

                Constructor<?> constructor =
                    clazz.getConstructor(
                        double.class,
                        double.class
                    );

                return constructor.newInstance(
                    MAX_POWER_WATTS,
                    STATIC_POWER_PERCENT
                );

            } catch (Exception ignored) {
                // Try the next known power model class name.
            }
        }

        return null;
    }

    private static double positiveMetric(
        Object target,
        String methodName
    ) {

        Object value =
            callNoArg(target, methodName);

        if (!(value instanceof Number)) {
            return 0.0;
        }

        double number =
            ((Number) value).doubleValue();

        if (Double.isNaN(number)
            || Double.isInfinite(number)
            || number < 0.0) {
            return 0.0;
        }

        return number;
    }

    private static Object callNoArg(
        Object target,
        String methodName
    ) {

        return call(
            target,
            methodName,
            new Class<?>[0]
        );
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
            Method method =
                target.getClass().getMethod(
                    methodName,
                    parameterTypes
                );

            return method.invoke(target, args);

        } catch (Exception ignored) {
            return null;
        }
    }

    private static int collectionSize(Object value) {

        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).size();
        }

        return 0;
    }

    private static void printFirstAssignments(
        List<Cloudlet> cloudlets,
        int count
    ) {

        int limit =
            Math.min(count, cloudlets.size());

        for (int i = 0; i < limit; i++) {

            Cloudlet cloudlet =
                cloudlets.get(i);

            System.out.println(
                "Cloudlet " + cloudlet.getId()
                    + " -> VM " + cloudlet.getVm().getId()
            );
        }
    }

    public static class InferenceRow {

        private final int vmIndex;
        private final int selected;
        private final double avgQueue;
        private final double avgUtilization;
        private final double avgCarbon;
        private final double avgReward;

        InferenceRow(
            int vmIndex,
            int selected,
            double avgQueue,
            double avgUtilization,
            double avgCarbon,
            double avgReward
        ) {
            this.vmIndex = vmIndex;
            this.selected = selected;
            this.avgQueue = avgQueue;
            this.avgUtilization = avgUtilization;
            this.avgCarbon = avgCarbon;
            this.avgReward = avgReward;
        }

        public int getVmIndex() {
            return vmIndex;
        }

        public int getSelected() {
            return selected;
        }

        public double getAvgQueue() {
            return avgQueue;
        }

        public double getAvgUtilization() {
            return avgUtilization;
        }

        public double getAvgCarbon() {
            return avgCarbon;
        }

        public double getAvgReward() {
            return avgReward;
        }
    }

    private static class InferenceAccumulator {

        private final int vmIndex;
        private int selected;
        private double queueSum;
        private double carbonSum;
        private double rewardSum;
        private double busyTimeSum;
        private double episodeDuration = 1.0;

        InferenceAccumulator(int vmIndex) {
            this.vmIndex = vmIndex;
        }

        void add(
            Decision decision,
            double reward,
            double busyTime,
            double episodeDuration
        ) {

            selected++;
            queueSum += decision.queueLengthAtSelection;
            carbonSum += decision.carbonIntensity;
            rewardSum += reward;
            busyTimeSum += busyTime;
            this.episodeDuration = Math.max(1.0, episodeDuration);
        }

        InferenceRow toRow() {

            if (selected == 0) {
                return new InferenceRow(
                    vmIndex,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    0.0
                );
            }

            return new InferenceRow(
                vmIndex,
                selected,
                queueSum / selected,
                Math.min(1.0, busyTimeSum / episodeDuration),
                carbonSum / selected,
                rewardSum / selected
            );
        }
    }

    private static class QLearningAgent {

        private final Map<State, double[]> qTable =
            new HashMap<>();

        private final int actionCount;
        private final double alpha;
        private final double gamma;
        private final double minEpsilon;
        private final double epsilonDecay;
        private final Random random = new Random(42);

        private double epsilon;

        QLearningAgent(
            int actionCount,
            double alpha,
            double gamma,
            double epsilon,
            double minEpsilon,
            double epsilonDecay
        ) {
            this.actionCount = actionCount;
            this.alpha = alpha;
            this.gamma = gamma;
            this.epsilon = epsilon;
            this.minEpsilon = minEpsilon;
            this.epsilonDecay = epsilonDecay;
        }

        int chooseAction(State state, boolean training) {

            if (training && random.nextDouble() < epsilon) {
                return random.nextInt(actionCount);
            }

            double[] values =
                qTable.computeIfAbsent(
                    state,
                    ignored -> new double[actionCount]
                );

            int bestAction = 0;
            double bestValue = values[0];
            int ties = 1;

            for (int action = 1; action < values.length; action++) {

                if (values[action] > bestValue) {
                    bestValue = values[action];
                    bestAction = action;
                    ties = 1;
                } else if (Double.compare(values[action], bestValue) == 0) {
                    ties++;
                    if (random.nextInt(ties) == 0) {
                        bestAction = action;
                    }
                }
            }

            return bestAction;
        }

        void update(
            State state,
            int action,
            double reward,
            State nextState
        ) {

            double[] values =
                qTable.computeIfAbsent(
                    state,
                    ignored -> new double[actionCount]
                );

            double oldValue =
                values[action];

            double bestFuture =
                nextState == null
                    ? 0.0
                    : max(
                    qTable.computeIfAbsent(
                        nextState,
                        ignored -> new double[actionCount]
                    )
                );

            values[action] =
                oldValue
                    + alpha
                    * (reward + gamma * bestFuture - oldValue);
        }

        private double max(double[] values) {

            double max = values[0];

            for (int i = 1; i < values.length; i++) {
                max = Math.max(max, values[i]);
            }

            return max;
        }

        void decayEpsilon() {
            epsilon =
                Math.max(
                    minEpsilon,
                    epsilon * epsilonDecay
                );
        }

        double getEpsilon() {
            return epsilon;
        }

        void setEpsilon(double epsilon) {
            this.epsilon = epsilon;
        }

        int stateCount() {
            return qTable.size();
        }
    }

    private static class State {

        private final String key;

        State(String key) {
            this.key = key;
        }

        static State from(
            Cloudlet cloudlet,
            List<Vm> vmList,
            int carbonSlot
        ) {

            StringBuilder builder =
                new StringBuilder();

            builder
                .append("W")
                .append(workloadCategory(cloudlet));

            for (int i = 0; i < vmList.size(); i++) {

                Vm vm =
                    vmList.get(i);

                builder
                    .append("|V")
                    .append(i)
                    .append("U")
                    .append(
                        utilizationCategory(
                            vmUtilization(vm)
                        )
                    )
                    .append("Q")
                    .append(
                        queueCategory(
                            queueLength(vm)
                        )
                    )
                    .append("C")
                    .append(
                        CarbonIntensityLoader.getCarbonCategory(
                            i,
                            carbonSlot
                        )
                    );
            }

            return new State(builder.toString());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof State
                && key.equals(((State) other).key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    private static class Decision {

        private final Cloudlet cloudlet;
        private final Vm vm;
        private final int vmIndex;
        private final State state;
        private final int carbonSlot;
        private final double carbonIntensity;
        private final int queueLengthAtSelection;

        Decision(
            Cloudlet cloudlet,
            Vm vm,
            int vmIndex,
            State state,
            int carbonSlot,
            double carbonIntensity,
            int queueLengthAtSelection
        ) {
            this.cloudlet = cloudlet;
            this.vm = vm;
            this.vmIndex = vmIndex;
            this.state = state;
            this.carbonSlot = carbonSlot;
            this.carbonIntensity = carbonIntensity;
            this.queueLengthAtSelection = queueLengthAtSelection;
        }
    }

    private static class EpisodeResult {

        private int finishedCloudlets;
        private double totalReward;
        private double totalEnergyKWh;
        private double totalCarbonGrams;

        private final Map<Integer, Integer> vmSelections =
            new HashMap<>();

        private final List<InferenceRow> inferenceRows =
            new ArrayList<>();
    }
}
