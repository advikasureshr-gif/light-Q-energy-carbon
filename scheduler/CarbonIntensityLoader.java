package org.cloudsimplus.myproject;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CarbonIntensityLoader {

    // ✅ 1. Multiple carbon profiles (one file per VM/region profile)
    private static final String[] FILES = {
        "/January4.xlsx",
        "/January12.xlsx",
        "/January15.xlsx",
        "/April9.xlsx",
        "/October24.xlsx"
    };
    private static final int EXPECTED_SLOTS = 48;
    // ✅ 2. Store multiple profiles — outer list = profile, inner list = slots
    private static final List<List<Double>> carbonValues = new ArrayList<>();
    private static final List<List<String>> carbonLevels = new ArrayList<>();

    // ✅ 7. Label → category mapping (avoids inventing thresholds in the scheduler)
    private static final Map<String, Integer> LEVEL_TO_CATEGORY = Map.of(
        "very low",  0,
        "low",       1,
        "moderate",  2,
        "medium",    2,   // support both if encountered
        "high",      3,
        "very high", 4
    );

    static {
        // ✅ 3. Load every file; each call appends one profile to the master lists
        for (String file : FILES) {
            loadData(file);
        }
    }

    // ✅ 3. loadData now accepts a filename and appends one profile per call
    private static void loadData(String fileName) {

        List<Double> values = new ArrayList<>();
        List<String> levels = new ArrayList<>();

        InputStream is = CarbonIntensityLoader.class.getResourceAsStream(fileName);
        if (is == null) {
            throw new RuntimeException("Carbon dataset not found: " + fileName);
        }
        try (
            Workbook workbook = new XSSFWorkbook(is)
        ) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean firstRow = true;

            for (Row row : sheet) {

                if (firstRow) {
                    firstRow = false;
                    continue;
                }

                Cell actualCell = row.getCell(5);
                Cell levelCell  = row.getCell(6);

                if (actualCell == null || levelCell == null)
                    continue;

                // ✅ 6. Actual carbon intensity value is always kept
                double actualIntensity = actualCell.getNumericCellValue();

                String intensityLevel =
                    levelCell.getStringCellValue()
                        .trim()
                        .toLowerCase();

                values.add(actualIntensity);
                levels.add(intensityLevel);
            }
            if (values.size() != EXPECTED_SLOTS) {
                throw new RuntimeException(
                    fileName + " contains " +
                        values.size() +
                        " slots. Expected " + EXPECTED_SLOTS + "."
                );
            }
            carbonValues.add(values);
            carbonLevels.add(levels);

            System.out.printf(
                "Loaded carbon profile from %s — %d slots%n",
                fileName, values.size()
            );

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to load carbon intensity data from: " + fileName, e);
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * ✅ 4. Returns the actual carbon intensity (gCO₂/kWh) for a given VM and slot.
     * Profile selection wraps automatically when vmId exceeds the number of datasets.
     */
    public static double getCarbonIntensity(int vmId, int slot) {
        List<Double> profile = profileFor(vmId, carbonValues);
        return profile.get(slot % profile.size());
    }

    /**
     * ✅ 5. Returns the raw label ("low", "moderate", "high", "very high")
     * for a given VM and slot, sourced directly from the dataset.
     */
    public static String getCarbonLevel(int vmId, int slot) {
        List<String> profile = profileFor(vmId, carbonLevels);
        return profile.get(slot % profile.size());
    }

    /**
     * ✅ 7. Returns a numeric category for use in state encoding:
     *   very low=0 | low=1 | moderate=2 | high=3 | very high=4
     * No scheduler-side thresholds needed.
     */
    public static int getCarbonCategory(int vmId, int slot) {
        String level = getCarbonLevel(vmId, slot);
        return LEVEL_TO_CATEGORY.getOrDefault(level, 1); // default → moderate
    }

    /** Number of profiles loaded (equals FILES.length on success). */
    public static int profileCount() {
        return carbonValues.size();
    }

    /** Number of slots in the profile assigned to this VM. */
    public static int slotCount(int vmId) {
        return profileFor(vmId, carbonValues).size();
    }

    // ─── Internal helper ─────────────────────────────────────────────────────

    /** Selects the right profile list for a given vmId, wrapping if necessary. */
    private static <T> List<T> profileFor(int vmId, List<List<T>> profiles) {
        return profiles.get(vmId % profiles.size());
    }
}

