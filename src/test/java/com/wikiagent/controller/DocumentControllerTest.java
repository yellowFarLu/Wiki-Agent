package com.wikiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.task.TaskQueryService;
import com.wikiagent.application.task.TaskSubmissionService;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskPayload;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.dto.DocumentView;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传幂等（uploadViaTask）：既有任务幂等返回；终态失败任务不得阻塞同内容重新入库。
 */
class DocumentControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KbDocumentRepo docRepo;
    private IngestionService ingestionService;
    private TaskSubmissionService submission;
    private TaskQueryService taskQuery;
    private DocumentController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        docRepo = mock(KbDocumentRepo.class);
        ingestionService = mock(IngestionService.class);
        submission = mock(TaskSubmissionService.class);
        taskQuery = mock(TaskQueryService.class);
        ObjectProvider<TaskSubmissionService> submissionProvider = mock(ObjectProvider.class);
        ObjectProvider<TaskQueryService> queryProvider = mock(ObjectProvider.class);
        when(submissionProvider.getIfAvailable()).thenReturn(submission);
        when(queryProvider.getIfAvailable()).thenReturn(taskQuery);
        controller = new DocumentController(docRepo, ingestionService, submissionProvider, queryProvider);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "demo.pdf", "application/pdf", "demo-bytes".getBytes());
    }

    private TaskInstance task(String taskId, TaskStatus status, String docId) {
        ObjectNode payload = MAPPER.createObjectNode().put("docId", docId);
        Instant now = Instant.now();
        return new TaskInstance(taskId, "INGEST", "ingest:abc:anonymous", status, payload,
                3, 3, 0, null, null, null, null, "anonymous", null,
                now, null, null, null, null, null, 0, now, now);
    }

    private TaskInstance submittedTask(String bizKey) {
        Instant now = Instant.now();
        return new TaskInstance("tsk-new", "INGEST", bizKey, TaskStatus.PENDING, null,
                0, 3, 0, null, null, null, null, "anonymous", null,
                now, null, null, null, null, null, 0, now, now);
    }

    @Test
    void duplicateUploadReturnsExistingDocAndTask() throws Exception {
        KbDocument orig = new KbDocument();
        orig.setId("doc-1");
        orig.setFilename("demo.pdf");
        orig.setDocType("pdf");
        orig.setStatus(KbDocument.READY);
        when(taskQuery.findByBizKey(any())).thenReturn(Optional.of(task("tsk-1", TaskStatus.COMPLETED, "doc-1")));
        when(docRepo.findById("doc-1")).thenReturn(Optional.of(orig));

        DocumentView view = controller.upload(file(), null, null, null, null);

        assertThat(view.id()).isEqualTo("doc-1");
        assertThat(view.taskId()).isEqualTo("tsk-1");
        assertThat(view.duplicate()).isTrue();
        verify(submission, never()).submit(any());
    }

    @Test
    void failedTerminalTaskDoesNotBlockReingestOfSameContent() throws Exception {
        // 首次入库 FAILED 且文档已删：重新上传必须派生新 bizKey 提交新任务，而非幂等回旧失败任务
        when(taskQuery.findByBizKey(any()))
                .thenReturn(Optional.of(task("tsk-old", TaskStatus.FAILED, "doc-deleted")));
        when(docRepo.findById("doc-deleted")).thenReturn(Optional.empty());
        when(submission.submit(any())).thenAnswer(inv -> submittedTask(inv.<TaskPayload>getArgument(0).bizKey()));

        DocumentView view = controller.upload(file(), null, null, null, null);

        ArgumentCaptor<TaskPayload> captor = ArgumentCaptor.forClass(TaskPayload.class);
        verify(submission).submit(captor.capture());
        String bizKey = captor.getValue().bizKey();
        assertThat(bizKey).startsWith("ingest:").contains(":anonymous:retry:").endsWith(view.id());
        assertThat(view.taskId()).isEqualTo("tsk-new");
        assertThat(view.duplicate()).isFalse();
        assertThat(view.status()).isEqualTo(KbDocument.PARSING);
    }

    @Test
    void failedOrigDocAlsoTriggersReingest() throws Exception {
        KbDocument orig = new KbDocument();
        orig.setId("doc-failed");
        orig.setFilename("demo.pdf");
        orig.setDocType("pdf");
        orig.setStatus(KbDocument.FAILED);
        when(taskQuery.findByBizKey(any()))
                .thenReturn(Optional.of(task("tsk-old", TaskStatus.FAILED, "doc-failed")));
        when(docRepo.findById("doc-failed")).thenReturn(Optional.of(orig));
        when(submission.submit(any())).thenAnswer(inv -> submittedTask(inv.<TaskPayload>getArgument(0).bizKey()));

        DocumentView view = controller.upload(file(), null, null, null, null);

        assertThat(view.id()).isNotEqualTo("doc-failed");
        assertThat(view.duplicate()).isFalse();
        verify(submission).submit(any());
    }

    @Test
    void freshUploadSubmitsWithBaseBizKey() throws Exception {
        when(taskQuery.findByBizKey(any())).thenReturn(Optional.empty());
        when(submission.submit(any())).thenAnswer(inv -> submittedTask(inv.<TaskPayload>getArgument(0).bizKey()));

        DocumentView view = controller.upload(file(), null, null, null, null);

        ArgumentCaptor<TaskPayload> captor = ArgumentCaptor.forClass(TaskPayload.class);
        verify(submission).submit(captor.capture());
        assertThat(captor.getValue().bizKey()).matches("ingest:[0-9a-f]{64}:anonymous");
        assertThat(view.duplicate()).isFalse();
    }
}
