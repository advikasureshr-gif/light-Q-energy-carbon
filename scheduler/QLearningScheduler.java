package org.cloudsimplus.myproject;

import org.cloudsimplus.cloudlets.Cloudlet;

import java.util.*;

/**
 * Q-Learning based cloudlet-to-VM scheduler.
 *
 * Implements the adaptive resource scheduling strategy described in:
 * "Adaptive scheduling strategy for cloud computing resources based on
 *  Q-learning algorithm" (Xu et al., Mechanics & Industry 27, 4, 2026)
 *
 * Key mappings from paper to this implementation:
 *   - State  : VM load levels encoded as LOW/MEDIUM/HIGH per VM (Section 2.2)
 *   - Action : Which VM index to assign the incoming cloudlet to (Section 2.3)
 *   - Reward : r = ω1·Uavg − ω2·D  (paper eq. 5)
 *   - Update : Bellman equation      (paper eq. 4)
 *   - Policy : ε-greedy with linear decay from 0.9 → 0.1 (Section 2.4)
 */
public class QlearningScheduler {

    // -------------------------------------------------------------------------
    // Hyperparameters — taken directly from paper (Section 2.4)
    // -------------------------------------------------------------------------
    private static final double EPSILON_INITIAL = 0.9;  // Start with high exploration
    private static final double EPSILON_MIN     = 0.1;  // Always keep some exploration
    private static final double EPSILON_DECAY   = 0.05; // Decrease by 0.05 every 10 episodes
    private static final double ALPHA           = 0.1;  // Learning rate
    private static final double GAMMA           = 0.9;  // Discount factor

    // Reward weights (paper eq. 5): r = ω1·Uavg − ω2·D
    // ω1 prioritises high utilisation; ω2 penalises load imbalance
    private static final double OMEGA_1 = 0.6;
    private static final double OMEGA_2 = 0.4;

    // -------------------------------------------------------------------------
    // State-space thresholds (paper Section 2.2)
    // CPU/memory load ranges: LOW = 0–40%, MEDIUM = 40–70%, HIGH = 70–100%
    // -------------------------------------------------------------------------
    private static final double LOW_THRESHOLD  = 0.40;
    private static final double HIGH_THRESHOLD = 0.70;

    private static final int LOAD_LOW    = 0;
    private static final int LOAD_MEDIUM = 1;
    private static final int LOAD_HIGH   = 2;

    // -------------------------------------------------------------------------
    // Training configuration
    // -------------------------------------------------------------------------
    public static final int TRAINING_EPISODES = 150; // Matches paper's Figure 2 x-axis

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------
    private final int numVms;
    private final Map<String, double[]> qTable; // state-key → Q-values[numVms]
    private double epsilon;
    private final Random random;

    // Training diagnostics
    private final List<Double> episodeRewards = new ArrayList<>();

    public QlearningScheduler(int numVms) {
        this.numVms  = numVms;
        this.qTable  = new HashMap<>();
        this.epsilon = EPSILON_INITIAL;
        this.random  = new Random(42); // Fixed seed for reproducibility
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Trains the Q-table over multiple episodes.
     *
     * Each episode re-runs the full cloudlet list from scratch so the agent
     * experiences many assignment orderings (exploration is driven by ε).
     *
     * @param cloudlets    The cloudlets to be scheduled
     * @param vmCapacities Estimated total capacity (MI) for each VM,
     *                     used to compute the utilisation ratio for state encoding.
     *                     Set to  totalCloudletLength / numVms  for a fair-share baseline.
     */
    public void train(List<Cloudlet> cloudlets, long[] vmCapacities) {
        System.out.println("\n=== Q-Learning Training ===");
        System.out.printf("Episodes=%d | α=%.2f | γ=%.2f | ε_init=%.2f%n%n",
            TRAINING_EPISODES, ALPHA, GAMMA, EPSILON_INITIAL);

        for (int episode = 0; episode < TRAINING_EPISODES; episode++) {

            double[] vmLoads   = new double[numVms]; // Accumulated MI per VM this episode
            double   totalReward = 0.0;

            // Shuffle cloudlets each episode so the agent sees varied orderings
            List<Cloudlet> shuffled = new ArrayList<>(cloudlets);
            Collections.shuffle(shuffled, random);

            for (Cloudlet cloudlet : shuffled) {

                // --- Step 1: Observe current state ---
                String state = encodeState(vmLoads, vmCapacities);

                // --- Step 2: Identify overloaded VMs (HIGH state = avoid if possible) ---
                boolean[] overloaded = buildOverloadMask(vmLoads, vmCapacities);

                // --- Step 3: Select action via ε-greedy ---
                int vmChoice = selectAction(state, overloaded);

                // --- Step 4: Apply action (assign cloudlet to chosen VM) ---
                vmLoads[vmChoice] += cloudlet.getLength();

                // --- Step 5: Observe next state and compute reward (eq. 5) ---
                String nextState = encodeState(vmLoads, vmCapacities);
                double reward    = computeReward(vmLoads, vmCapacities);
                totalReward += reward;

                // --- Step 6: Bellman update (eq. 4) ---
                updateQValue(state, vmChoice, reward, nextState);
            }

            // Decay ε every 10 episodes (paper Section 2.4)
            decayEpsilon(episode + 1);

            double avgReward = totalReward / cloudlets.size();
            episodeRewards.add(avgReward);

            // Print progress at meaningful checkpoints
            if ((episode + 1) % 30 == 0 || episode == 0) {
                System.out.printf("Episode %3d | ε=%.2f | Avg Reward=%.5f | Q-states=%d%n",
                    episode + 1, epsilon, avgReward, qTable.size());
            }
        }

        System.out.println("\nTraining complete.");
        System.out.println("Q-table size (unique states visited): " + qTable.size());
    }

    /**
     * Applies the trained policy (ε = 0, pure exploitation) to produce a
     * final cloudlet → VM-index assignment map.
     *
     * Must be called after {@link #train}.
     */
    public Map<Cloudlet, Integer> schedule(List<Cloudlet> cloudlets, long[] vmCapacities) {
        double savedEpsilon = epsilon;
        epsilon = 0.0; // Pure exploitation: always pick the best known action

        Map<Cloudlet, Integer> assignments = new LinkedHashMap<>();
        double[] vmLoads = new double[numVms];

        for (Cloudlet cloudlet : cloudlets) {
            String   state    = encodeState(vmLoads, vmCapacities);
            boolean[] overloaded = buildOverloadMask(vmLoads, vmCapacities);
            int vmChoice = selectAction(state, overloaded);

            assignments.put(cloudlet, vmChoice);
            vmLoads[vmChoice] += cloudlet.getLength();
        }

        epsilon = savedEpsilon;
        printLoadDistribution(vmLoads, vmCapacities);
        return assignments;
    }

    // =========================================================================
    // State encoding  (paper Section 2.2 — eq. 3)
    // =========================================================================

    /**
     * Discretises a continuous utilisation ratio into LOW / MEDIUM / HIGH.
     * Matches the paper's three-interval partition (0–40%, 40–70%, 70–100%).
     */
    private int discretizeLoad(double ratio) {
        if (ratio < LOW_THRESHOLD)  return LOAD_LOW;
        if (ratio < HIGH_THRESHOLD) return LOAD_MEDIUM;
        return LOAD_HIGH;
    }

    /**
     * Encodes the full VM load vector into a compact string state key.
     * e.g. five VMs at LOW/LOW/MEDIUM/LOW/HIGH → "00120"
     *
     * Corresponds to S(t) = {Ui(t) | i ∈ [1,n]}  (paper eq. 3)
     */
    private String encodeState(double[] vmLoads, long[] vmCapacities) {
        StringBuilder sb = new StringBuilder(numVms);
        for (int i = 0; i < numVms; i++) {
            double ratio = (vmCapacities[i] > 0)
                ? vmLoads[i] / vmCapacities[i]
                : 0.0;
            sb.append(discretizeLoad(ratio));
        }
        return sb.toString();
    }

    // =========================================================================
    // Action selection  (paper Section 2.3 & 2.4)
    // =========================================================================

    /**
     * Returns a boolean mask indicating which VMs are currently in HIGH state.
     * The scheduler will prefer to avoid these unless there is no alternative
     * (mirrors the paper's action: "migrate VM" or "reduce resources" on high load).
     */
    private boolean[] buildOverloadMask(double[] vmLoads, long[] vmCapacities) {
        boolean[] mask = new boolean[numVms];
        for (int i = 0; i < numVms; i++) {
            double ratio = (vmCapacities[i] > 0) ? vmLoads[i] / vmCapacities[i] : 0.0;
            mask[i] = (discretizeLoad(ratio) == LOAD_HIGH);
        }
        return mask;
    }

    /**
     * Selects an action using ε-greedy exploration/exploitation.
     *
     * Exploration  → random VM from non-overloaded candidates
     * Exploitation → VM with highest Q-value from non-overloaded candidates
     *
     * If ALL VMs are overloaded the constraint is relaxed so scheduling
     * always proceeds (fallback to any VM).
     */
    private int selectAction(String state, boolean[] overloaded) {
        // Build candidate list: prefer non-overloaded VMs
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < numVms; i++) {
            if (!overloaded[i]) candidates.add(i);
        }
        if (candidates.isEmpty()) {
            // Fallback: all VMs are available (overload constraint relaxed)
            for (int i = 0; i < numVms; i++) candidates.add(i);
        }

        if (random.nextDouble() < epsilon) {
            // --- Explore: random action ---
            return candidates.get(random.nextInt(candidates.size()));
        }

        // --- Exploit: highest Q-value among candidates ---
        double[] qValues = getOrInitQValues(state);
        int bestAction = candidates.get(0);
        for (int action : candidates) {
            if (qValues[action] > qValues[bestAction]) {
                bestAction = action;
            }
        }
        return bestAction;
    }

    // =========================================================================
    // Reward function  (paper eq. 5)
    // =========================================================================

    /**
     * Computes r = ω1·Uavg − ω2·D
     *
     * Uavg = average resource utilisation across all VMs
     * D    = load imbalance (standard deviation of utilisation, eq. 7)
     *
     * High Uavg is good (positive); high D is bad (penalised).
     */
    private double computeReward(double[] vmLoads, long[] vmCapacities) {
        double[] utils = new double[numVms];
        double   uAvg  = 0.0;

        for (int i = 0; i < numVms; i++) {
            // Cap utilisation at 1.0 so overloaded VMs don't inflate the reward
            utils[i] = (vmCapacities[i] > 0)
                ? Math.min(vmLoads[i] / vmCapacities[i], 1.0)
                : 0.0;
            uAvg += utils[i];
        }
        uAvg /= numVms;

        // D = sqrt( (1/n) * Σ(Ui - Uavg)² )   — paper eq. 7
        double variance = 0.0;
        for (double u : utils) {
            variance += Math.pow(u - uAvg, 2);
        }
        double D = Math.sqrt(variance / numVms);

        return OMEGA_1 * uAvg - OMEGA_2 * D;
    }

    // =========================================================================
    // Q-table update  (paper eq. 4)
    // =========================================================================

    /**
     * Bellman equation update:
     *   Q(s,a) ← Q(s,a) + α · [ r + γ · max_a' Q(s',a') − Q(s,a) ]
     */
    private void updateQValue(String state, int action,
                              double reward, String nextState) {
        double[] qValues     = getOrInitQValues(state);
        double[] nextQValues = getOrInitQValues(nextState);

        double maxNextQ = Arrays.stream(nextQValues).max().orElse(0.0);

        qValues[action] = qValues[action]
            + ALPHA * (reward + GAMMA * maxNextQ - qValues[action]);
    }

    /** Retrieves existing Q-values or initialises them to zero. */
    private double[] getOrInitQValues(String state) {
        return qTable.computeIfAbsent(state, k -> new double[numVms]);
    }

    // =========================================================================
    // ε decay  (paper Section 2.4 — linear decay every 10 rounds)
    // =========================================================================

    private void decayEpsilon(int episode) {
        if (episode % 10 == 0) {
            epsilon = Math.max(EPSILON_MIN, epsilon - EPSILON_DECAY);
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Prints the final VM load distribution and key metrics after scheduling.
     * Reports Uavg and D to mirror the paper's evaluation criteria.
     */
    public void printLoadDistribution(double[] vmLoads, long[] vmCapacities) {
        System.out.println("\n=== Final VM Load Distribution (Q-Learning Policy) ===");

        double[] utils = new double[numVms];
        double   uAvg  = 0.0;

        for (int i = 0; i < numVms; i++) {
            utils[i] = (vmCapacities[i] > 0)
                ? Math.min((double) vmLoads[i] / vmCapacities[i], 1.0)
                : 0.0;
            uAvg += utils[i];

            String level = switch (discretizeLoad(utils[i])) {
                case LOAD_LOW    -> "LOW";
                case LOAD_MEDIUM -> "MEDIUM";
                default          -> "HIGH";
            };
            System.out.printf("  VM %d | Accumulated Load = %,.0f MI | Util = %.3f | State = %s%n",
                i, vmLoads[i], utils[i], level);
        }
        uAvg /= numVms;

        double variance = 0.0;
        for (double u : utils) variance += Math.pow(u - uAvg, 2);
        double D = Math.sqrt(variance / numVms);

        System.out.println();
        System.out.printf("  Uavg (avg utilisation) = %.4f%n", uAvg);
        System.out.printf("  D    (load imbalance)  = %.4f%n", D);
        System.out.printf("  Reward r = ω1·Uavg − ω2·D = %.4f%n",
            OMEGA_1 * uAvg - OMEGA_2 * D);
    }
}

