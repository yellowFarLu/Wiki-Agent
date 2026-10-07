package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.CustomsResult;
import com.wikiagent.domain.business.MappingRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CustomsData 纯函数单测：同映射行两次派生一致；行数与映射表一致。
 */
class CustomsDataTest {

    @Test
    void 同小包号派生值稳定() {
        List<MappingRow> mapping = List.of(
                new MappingRow("XBA001", "BIG001"),
                new MappingRow("XBA002", "BIG002"));
        List<CustomsResult.CustomsRow> a = CustomsData.buildRows(mapping);
        List<CustomsResult.CustomsRow> b = CustomsData.buildRows(mapping);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(2);
    }

    @Test
    void 派生字段非空且重量价值为正() {
        List<CustomsResult.CustomsRow> rows = CustomsData.buildRows(List.of(new MappingRow("XBA001", "BIG001")));
        CustomsResult.CustomsRow row = rows.get(0);
        assertThat(row.country()).isNotBlank();
        assertThat(row.declaredName()).isNotBlank();
        assertThat(row.weightKg()).isGreaterThan(0d);
        assertThat(row.declaredValue()).isGreaterThan(0d);
        assertThat(row.currency()).isEqualTo("USD");
    }
}
