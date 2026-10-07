package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.MappingRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 清关映射表 Excel 解析器（纯静态，确定性校验）。
 * <p>
 * 校验规则（§2.4）：必须是 xlsx；首行必须含「小包号」「大包号」两列；至少 1 行数据。
 * 不符抛 {@link IllegalArgumentException}，消息含具体列名要求，供上传接口返回 422。
 */
public final class MappingExcelParser {

    public static final String COL_SMALL = "小包号";
    public static final String COL_BIG = "大包号";

    private MappingExcelParser() {}

    /**
     * 解析映射表。
     *
     * @param in xlsx 输入流
     * @return 映射行列表（≥1 行）
     * @throws IllegalArgumentException 非 xlsx / 缺列 / 0 行数据
     */
    public static List<MappingRow> parse(InputStream in) throws IOException {
        List<MappingRow> rows = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("Excel 为空，请上传含「小包号、大包号」两列的 xlsx");
            }
            Row header = sheet.getRow(0);
            if (header == null) {
                throw new IllegalArgumentException("缺少表头，首行必须包含「小包号」「大包号」两列");
            }
            int smallIdx = -1;
            int bigIdx = -1;
            for (int c = 0; c <= header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c);
                if (cell == null) {
                    continue;
                }
                String v = cellText(cell);
                if (COL_SMALL.equals(v)) {
                    smallIdx = c;
                } else if (COL_BIG.equals(v)) {
                    bigIdx = c;
                }
            }
            if (smallIdx < 0 || bigIdx < 0) {
                throw new IllegalArgumentException(
                        "表头缺少必需列，必须同时包含「小包号」和「大包号」两列");
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String small = cellText(row.getCell(smallIdx));
                String big = cellText(row.getCell(bigIdx));
                if (small.isBlank() && big.isBlank()) {
                    continue;
                }
                if (small.isBlank() || big.isBlank()) {
                    throw new IllegalArgumentException(
                            "第 " + (r + 1) + " 行存在空的小包号或大包号，请补齐后再上传");
                }
                rows.add(new MappingRow(small, big));
            }
        } catch (IOException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文件解析失败，请确认上传的是 .xlsx 格式的 Excel", e);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("映射表无数据行，请至少填写一行「小包号、大包号」");
        }
        return rows;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                // 整数按长整型输出（避免科学计数），否则保留原值
                yield (d == Math.floor(d) && !Double.isInfinite(d))
                        ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
