package com.wikiagent.infrastructure.tool;

import com.wikiagent.application.business.BusinessFileService;
import com.wikiagent.domain.business.CustomsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 业务工具：生成清关信息（唯一产物类）。
 * <p>
 * PERO 侧的薄适配器：真实生成（幂等 + 落盘 + V24 登记）由 {@link BusinessFileService} 承担，
 * 本类只做入参校验与结果渲染为 Observation 文本。
 */
@Component
public class GenerateCustomsInfoTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateCustomsInfoTool.class);

    public static final String NAME = "generate_customs_info";

    private final BusinessFileService fileService;

    public GenerateCustomsInfoTool(BusinessFileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 生成清关信息。
     *
     * @param mappingFileId 已通过槽位证据校验的映射表 fileId
     * @param taskId        当前业务任务 biz_task_id（可空，用于产物软关联）
     * @return Observation 文本（含 fileId + 行数 + 预览）
     */
    public String execute(String mappingFileId, String taskId) {
        if (mappingFileId == null || mappingFileId.isBlank()) {
            return "[generate_customs_info] 缺少映射表 fileId";
        }
        try {
            CustomsResult r = fileService.generateCustoms(mappingFileId, taskId);
            return render(r);
        } catch (Exception e) {
            log.warn("清关信息生成失败 mappingFileId={}: {}", mappingFileId, e.getMessage());
            return "[generate_customs_info] 生成失败: " + e.getMessage();
        }
    }

    private static String render(CustomsResult r) {
        StringBuilder sb = new StringBuilder("已生成清关信息文件（fileId=").append(r.fileId())
                .append("，共 ").append(r.rowCount()).append(" 行）：\n");
        for (CustomsResult.CustomsRow row : r.preview()) {
            sb.append("- ").append(row.smallPackageNo()).append(" → ").append(row.bigPackageNo())
                    .append("（").append(row.country()).append(" / ").append(row.declaredName())
                    .append(" / ").append(row.weightKg()).append("kg / ").append(row.declaredValue())
                    .append(" ").append(row.currency()).append("）\n");
        }
        return sb.toString();
    }
}
