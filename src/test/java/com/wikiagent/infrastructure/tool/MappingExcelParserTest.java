package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.MappingRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MappingExcelParser 确定性校验单测：合法 xlsx 解析；缺列/0 行/空行抛明确异常。
 */
class MappingExcelParserTest {

    @Test
    void 合法映射表解析出行() throws IOException {
        byte[] xlsx = xlsx(new String[][]{
                {"小包号", "大包号"},
                {"XBA001", "BIG001"},
                {"XBA002", "BIG002"}});
        List<MappingRow> rows = MappingExcelParser.parse(new ByteArrayInputStream(xlsx));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new MappingRow("XBA001", "BIG001"));
    }

    @Test
    void 缺大小包号列抛出异常() throws IOException {
        byte[] xlsx = xlsx(new String[][]{{"小包号", "备注"}, {"XBA001", "x"}});
        assertThatThrownBy(() -> MappingExcelParser.parse(new ByteArrayInputStream(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("小包号");
    }

    @Test
    void 零数据行抛出异常() throws IOException {
        byte[] xlsx = xlsx(new String[][]{{"小包号", "大包号"}});
        assertThatThrownBy(() -> MappingExcelParser.parse(new ByteArrayInputStream(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无数据");
    }

    @Test
    void 非xlsx内容抛出异常() {
        assertThatThrownBy(() -> MappingExcelParser.parse(new ByteArrayInputStream("not an excel".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] xlsx(String[][] data) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }
            wb.write(os);
            return os.toByteArray();
        }
    }
}
