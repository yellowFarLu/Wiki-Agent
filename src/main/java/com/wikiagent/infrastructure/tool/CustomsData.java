package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.CustomsResult;
import com.wikiagent.domain.business.MappingRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 清关信息纯生成函数（确定性，字节级可回放）：小包号 → 目的国/品名/重量/价值（哈希派生）。
 * 与 POI 写盘解耦，便于单测；文件落盘与 V24 登记由外层 BusinessFileService 负责。
 */
public final class CustomsData {

    private CustomsData() {}

    /** 清关 Excel 列头。 */
    static final String[] HEADERS = {"序号", "小包号", "大包号", "目的国", "申报品名", "申报重量", "申报价值", "币种"};

    /** 按映射行确定性生成清关行（同小包号必得同派生值）。 */
    public static List<CustomsResult.CustomsRow> buildRows(List<MappingRow> mappingRows) {
        List<CustomsResult.CustomsRow> out = new ArrayList<>(mappingRows.size());
        for (MappingRow m : mappingRows) {
            out.add(new CustomsResult.CustomsRow(
                    m.smallPackageNo(), m.bigPackageNo(),
                    MockDataUtil.country(m.smallPackageNo()),
                    MockDataUtil.declaredName(m.smallPackageNo()),
                    MockDataUtil.declaredWeightKg(m.smallPackageNo()),
                    MockDataUtil.declaredValue(m.smallPackageNo()),
                    "USD"));
        }
        return out;
    }

    /** 写清关 Excel（POI .xlsx）。 */
    public static void writeXlsx(List<CustomsResult.CustomsRow> rows, Path output) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (Workbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(output)) {
            Sheet sheet = wb.createSheet("清关信息");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }
            for (int i = 0; i < rows.size(); i++) {
                CustomsResult.CustomsRow r = rows.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(r.smallPackageNo());
                row.createCell(2).setCellValue(r.bigPackageNo());
                row.createCell(3).setCellValue(r.country());
                row.createCell(4).setCellValue(r.declaredName());
                row.createCell(5).setCellValue(r.weightKg());
                row.createCell(6).setCellValue(r.declaredValue());
                row.createCell(7).setCellValue(r.currency());
            }
            wb.write(os);
        }
    }
}
