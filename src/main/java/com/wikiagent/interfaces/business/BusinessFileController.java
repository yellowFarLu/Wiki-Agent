package com.wikiagent.interfaces.business;

import com.wikiagent.application.business.BusinessFileService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务文件接口：映射表上传（确定性校验）与清关产物下载。
 * <p>
 * 上传成功即视为授权（清关生成免人工批准，选 B）；下载凭 fileId 长期有效（历史会话可回看）。
 */
@RestController
@RequestMapping("/api/business/files")
public class BusinessFileController {

    private final BusinessFileService fileService;

    public BusinessFileController(BusinessFileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 上传清关映射表（multipart file）。
     * 校验：xlsx + 首行含「小包号、大包号」+ ≥1 行数据；不符返回 422。
     */
    @PostMapping("/mapping")
    public ResponseEntity<?> uploadMapping(@RequestParam("file") MultipartFile file,
                                           @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择要上传的映射表"));
        }
        try {
            BusinessFileService.MappingUpload result = fileService.uploadMapping(
                    file.getBytes(), file.getOriginalFilename(), file.getContentType(), userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileId", result.fileId());
            body.put("rowCount", result.rowCount());
            body.put("rows", result.rows());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "上传处理失败: " + messageOf(e)));
        }
    }

    /** 下载清关产物 / 映射表（凭 fileId）。 */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable String fileId) {
        try {
            BusinessFileService.FileDownload fd = fileService.download(fileId);
            Resource resource = new FileSystemResource(fd.path());
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }
            MediaType mediaType = fd.contentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(fd.contentType());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType);
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(fd.originalName(), StandardCharsets.UTF_8).build());
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
