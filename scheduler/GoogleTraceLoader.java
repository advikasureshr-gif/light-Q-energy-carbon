package org.cloudsimplus.myproject;

import org.apache.poi.ss.usermodel.*;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class GoogleTraceLoader {

    public static List<Cloudlet> loadCloudlets() {

        List<Cloudlet> cloudletList = new ArrayList<>();

        try {

            InputStream is =
                GoogleTraceLoader.class
                    .getClassLoader()
                    .getResourceAsStream("prepared_tasks.xlsx");

            Workbook workbook =
                WorkbookFactory.create(is);

            Sheet sheet =
                workbook.getSheetAt(0);

            for (Row row : sheet) {

                if (row.getRowNum() == 0)
                    continue; // Skip header

                double cpu =
                    row.getCell(1).getNumericCellValue();

                double memory =
                    row.getCell(2).getNumericCellValue();

                double duration =
                    row.getCell(3).getNumericCellValue();

                /*
                 * Temporary conversion
                 * We can tune this later.
                 */

                long length =
                    Math.max(1000,
                        (long)(duration / 100000));

                int pes = 1;

                Cloudlet cloudlet =
                    new CloudletSimple(length, pes)
                        .setUtilizationModel(
                            new UtilizationModelFull());

                cloudletList.add(cloudlet);
            }

            workbook.close();

            System.out.println(
                "Cloudlets loaded = " +
                    cloudletList.size());

        } catch(Exception e) {
            e.printStackTrace();
        }

        return cloudletList;
    }
}
