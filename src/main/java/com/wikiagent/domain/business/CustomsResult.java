package com.wikiagent.domain.business;

import java.util.List;

/**
 * 清关信息生成结果（generate_customs_info 工具返回）。
 *
 * @param fileId     生成文件的对外 ID（bf-xxxx，凭此下载）
 * @param fileName   文件名
 * @param rowCount   生成行数（= 映射表行数）
 * @param preview    预览前 N 行（清关字段派生值，供前端/文本渲染）
 */
public record CustomsResult(String fileId, String fileName, int rowCount,
                            List<CustomsRow> preview) {

    /** 单行清关信息（Mock 派生：目的国/品名/重量/价值由小包号哈希确定性生成）。 */
    public record CustomsRow(String smallPackageNo, String bigPackageNo, String country,
                             String declaredName, double weightKg, double declaredValue, String currency) {
    }
}
