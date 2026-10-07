package com.wikiagent.application.business;

import com.wikiagent.config.BusinessIntentProperties;
import com.wikiagent.domain.business.CustomsResult;
import com.wikiagent.domain.business.MappingRow;
import com.wikiagent.infrastructure.business.BusinessFileEntity;
import com.wikiagent.infrastructure.business.BusinessFileJpaDao;
import com.wikiagent.infrastructure.business.BusinessTaskJpaDao;
import com.wikiagent.infrastructure.tool.CustomsData;
import com.wikiagent.infrastructure.tool.MappingExcelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 业务文件服务：映射表上传（确定性校验 + checksum 内容去重）、清关生成（幂等 + 落盘 + V24 登记）、下载。
 * <p>
 * 文件落盘 {@code {fileBaseDir}/{fileId}/...}，存储相对路径；产物以 fileId 为唯一引用，历史会话仍可下载。
 * 生成清关幂等键 {@code customs:{mappingFileId}}（一份映射表只生成一份清关，ReAct 重放取回旧产物）。
 */
@Service
public class BusinessFileService {

    private static final Logger log = LoggerFactory.getLogger(BusinessFileService.class);

    /** 上传映射表结果。 */
    public record MappingUpload(String fileId, int rowCount, List<MappingRow> rows) {}

    /** 下载文件句柄。 */
    public record FileDownload(Path path, String originalName, String contentType) {}

    private final BusinessFileJpaDao fileDao;
    private final BusinessTaskJpaDao taskDao;
    private final IdempotencyService idempotency;
    private final Path baseDir;

    public BusinessFileService(BusinessFileJpaDao fileDao, BusinessTaskJpaDao taskDao,
                               IdempotencyService idempotency, BusinessIntentProperties props) {
        this.fileDao = fileDao;
        this.taskDao = taskDao;
        this.idempotency = idempotency;
        this.baseDir = Path.of(props.getFileBaseDir());
    }

    /** 上传映射表：校验 + 落盘 + V24 登记 + checksum 内容去重。 */
    @Transactional
    public MappingUpload uploadMapping(byte[] bytes, String originalName, String contentType, String userId)
            throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件为空");
        }
        List<MappingRow> rows = MappingExcelParser.parse(new ByteArrayInputStream(bytes));
        String checksum = sha256Hex(bytes);

        // 内容去重：同用户同内容返回既有 fileId（避免重复落盘）
        var existing = fileDao.findFirstByUserIdAndFileTypeAndChecksum(
                userId, BusinessFileEntity.TYPE_MAPPING, checksum);
        if (existing.isPresent()) {
            return new MappingUpload(existing.get().getFileId(), existing.get().getRowCount(), rows);
        }

        String fileId = newFileId();
        Path dir = baseDir.resolve(fileId);
        Path path = dir.resolve("mapping.xlsx");
        Files.createDirectories(dir);
        Files.write(path, bytes);

        BusinessFileEntity entity = new BusinessFileEntity(
                fileId, BusinessFileEntity.TYPE_MAPPING, originalName == null ? "mapping.xlsx" : originalName,
                relative(path), contentType, rows.size(), userId, checksum);
        fileDao.save(entity);
        log.info("映射表上传成功 fileId={} rows={} userId={}", fileId, rows.size(), userId);
        return new MappingUpload(fileId, rows.size(), rows);
    }

    /** 生成清关信息：幂等 + 读映射表 + 纯派生 + 落盘 + V24 登记。 */
    @Transactional
    public CustomsResult generateCustoms(String mappingFileId, String taskId) throws IOException {
        BusinessFileEntity mapping = fileDao.findByFileId(mappingFileId)
                .orElseThrow(() -> new IllegalArgumentException("映射表不存在: " + mappingFileId));
        String scope = mapping.getUserId() + ":customs";
        String idemKey = "customs:" + mappingFileId;
        String requestHash = IdempotencyService.requestHash(mappingFileId);

        var decision = idempotency.begin(scope, idemKey, requestHash);
        if (decision.decision() == IdempotencyService.IdemDecision.REPLAY) {
            // 回放旧产物：同样回填当前任务 result_file_id，网关据此发 file_ready
            if (taskId != null) {
                String replayFileId = decision.resultRef();
                taskDao.findByBizTaskId(taskId).ifPresent(t -> t.setResultFileId(replayFileId));
            }
            return loadCustomsResult(decision.resultRef());
        }
        if (decision.decision() == IdempotencyService.IdemDecision.IN_FLIGHT) {
            throw new IllegalStateException("清关信息正在生成中，请稍后重试");
        }
        if (decision.decision() == IdempotencyService.IdemDecision.CONFLICT) {
            throw new IllegalStateException("同一映射表请求载荷冲突，请重新上传");
        }

        try {
            List<MappingRow> rows = MappingExcelParser.parse(Files.newInputStream(baseDir.resolve(mapping.getStoragePath())));
            List<CustomsResult.CustomsRow> customsRows = CustomsData.buildRows(rows);

            String fileId = newFileId();
            String fileName = "清关信息_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
            Path dir = baseDir.resolve(fileId);
            Path path = dir.resolve(fileName);
            CustomsData.writeXlsx(customsRows, path);

            BusinessFileEntity entity = new BusinessFileEntity(
                    fileId, BusinessFileEntity.TYPE_CUSTOMS, fileName, relative(path),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    customsRows.size(), mapping.getUserId(), null);
            entity.setMappingFileId(mappingFileId);
            entity.setBizTaskId(taskId);
            fileDao.save(entity);

            // 回填业务任务 result_file_id，供网关发 file_ready
            if (taskId != null) {
                taskDao.findByBizTaskId(taskId).ifPresent(t -> t.setResultFileId(fileId));
            }

            idempotency.complete(scope, idemKey, fileId);
            return new CustomsResult(fileId, fileName, customsRows.size(), preview(customsRows));
        } catch (IOException | RuntimeException e) {
            idempotency.fail(scope, idemKey, e.getMessage());
            throw e;
        }
    }

    /** 按 fileId 取下载句柄。 */
    public FileDownload download(String fileId) {
        BusinessFileEntity entity = fileDao.findByFileId(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在: " + fileId));
        return new FileDownload(baseDir.resolve(entity.getStoragePath()),
                entity.getOriginalName(), entity.getContentType());
    }

    /** 按 fileId 取文件元数据（供 file_ready 事件）。 */
    public BusinessFileEntity fileOf(String fileId) {
        return fileDao.findByFileId(fileId).orElse(null);
    }

    private CustomsResult loadCustomsResult(String fileId) throws IOException {
        BusinessFileEntity entity = fileDao.findByFileId(fileId)
                .orElseThrow(() -> new IllegalStateException("清关产物缺失: " + fileId));
        List<MappingRow> mappingRows = entity.getMappingFileId() == null ? List.of()
                : MappingExcelParser.parse(Files.newInputStream(
                        baseDir.resolve(fileDao.findByFileId(entity.getMappingFileId())
                                .orElseThrow().getStoragePath())));
        List<CustomsResult.CustomsRow> rows = CustomsData.buildRows(mappingRows);
        return new CustomsResult(entity.getFileId(), entity.getOriginalName(), entity.getRowCount(), preview(rows));
    }

    private static List<CustomsResult.CustomsRow> preview(List<CustomsResult.CustomsRow> rows) {
        return rows.size() <= 5 ? rows : rows.subList(0, 5);
    }

    private String relative(Path path) {
        return baseDir.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String newFileId() {
        return "bf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
