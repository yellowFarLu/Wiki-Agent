package com.wikiagent.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.ChatHistoryEntity;
import com.wikiagent.infrastructure.persistence.ChatHistoryJpaDao;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 对话历史持久化服务。
 * 保存用户问题和助手回答，支持按会话查询历史列表和消息。
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private static final TypeReference<List<Map<String, Object>>> SOURCE_LIST_TYPE = new TypeReference<>() {};

    private final ChatHistoryJpaDao dao;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(ChatHistoryJpaDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    /** 保存一条不带引用来源的消息（user / blocked）。 */
    public void save(String sessionId, String role, String content) {
        save(sessionId, role, content, null);
    }

    /**
     * 保存一条消息；assistant 回答可携带检索来源（与 SSE sources 事件同构），
     * 序列化为 JSON 落库，历史会话重开时还原正文角标与来源列表。
     */
    public void save(String sessionId, String role, String content,
                     List<RetrievalService.Source> sources) {
        try {
            String sourcesJson = null;
            if (sources != null && !sources.isEmpty()) {
                sourcesJson = objectMapper.writeValueAsString(sources);
            }
            dao.save(new ChatHistoryEntity(sessionId, role, content, sourcesJson));
        } catch (Exception e) {
            log.warn("保存对话历史失败 sessionId={} role={}: {}", sessionId, role, e.getMessage());
        }
    }

    /** 获取会话列表（按最近活跃降序），每个会话包含预览信息。 */
    public List<Map<String, Object>> listSessions() {
        List<String> sessionIds = dao.findDistinctSessionIds();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sid : sessionIds) {
            List<ChatHistoryEntity> msgs = dao.findBySessionIdOrderByCreatedAtAsc(sid);
            if (msgs.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", sid);
            item.put("messageCount", msgs.size());
            // 取第一条 user 消息作为预览与标题（产品化：标题=本会话内容主题，不暴露 sessionId）
            String firstUserMsg = msgs.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(ChatHistoryEntity::getContent)
                    .findFirst()
                    .orElse(msgs.get(0).getContent());
            String oneLine = firstUserMsg.replaceAll("\\s+", " ").trim();
            item.put("title", oneLine.length() > 40 ? oneLine.substring(0, 40) + "…" : oneLine);
            item.put("preview", oneLine.length() > 50 ? oneLine.substring(0, 50) + "…" : oneLine);
            item.put("lastTime", msgs.get(msgs.size() - 1).getCreatedAt());
            item.put("createdAt", msgs.get(0).getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /** 获取指定会话的全部消息（assistant 消息附带 sources 引用来源数组）。 */
    public List<Map<String, Object>> getMessages(String sessionId) {
        List<ChatHistoryEntity> msgs = dao.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatHistoryEntity m : msgs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            item.put("sources", parseSources(m.getSourcesJson()));
            result.add(item);
        }
        return result;
    }

    /** 反序列化来源 JSON；任何异常/空值都降级为空数组，不影响历史消息展示。 */
    private List<Map<String, Object>> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(sourcesJson, SOURCE_LIST_TYPE);
        } catch (Exception e) {
            log.warn("解析对话来源 JSON 失败，降级为空数组: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
