package com.wikiagent.application.graph;

import com.wikiagent.domain.graph.GraphEdge;
import com.wikiagent.domain.graph.GraphNode;
import com.wikiagent.entity.graph.GraphEntity;
import com.wikiagent.entity.graph.GraphRelation;
import com.wikiagent.repo.graph.GraphEntityRepo;
import com.wikiagent.repo.graph.GraphRelationRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GraphRAG 图感知检索服务：实体搜索、邻居扩展、文档子图查询。
 * 检索时通过实体匹配 + 1-hop 邻居扩展，补充向量检索未覆盖的关联上下文。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "wikiagent.graph.enabled", havingValue = "true", matchIfMissing = false)
public class GraphRagService {

    private static final Logger log = LoggerFactory.getLogger(GraphRagService.class);

    private final GraphEntityRepo entityRepo;
    private final GraphRelationRepo relationRepo;
    private final int maxNeighbors;

    public GraphRagService(GraphEntityRepo entityRepo,
                           GraphRelationRepo relationRepo,
                           @Value("${wikiagent.graph.max-neighbors:10}") int maxNeighbors) {
        this.entityRepo = entityRepo;
        this.relationRepo = relationRepo;
        this.maxNeighbors = maxNeighbors;
    }

    /** 图检索结果：匹配实体 + 扩展邻居 + 相关 chunkId 集合。 */
    public record GraphSearchResult(List<GraphNode> matchedEntities,
                                    List<GraphNode> neighborEntities,
                                    List<GraphEdge> edges,
                                    Set<String> relatedChunkIds) {
        public static GraphSearchResult empty() {
            return new GraphSearchResult(List.of(), List.of(), List.of(), Set.of());
        }
    }

    /** 实体浏览行（比 GraphNode 多创建时间）。 */
    public record EntityListRow(String id, String name, String type, String description,
                                String sourceDocId, String sourceChunkId, LocalDateTime createdAt) {}

    /** 实体分页结果：rows + 总数 + 当前有效类型清单（前端筛选下拉用）。 */
    public record EntityPage(List<EntityListRow> entities, long total, int page, int size,
                             List<String> types) {}

    /**
     * 按查询文本搜索实体，并做 1-hop 邻居扩展。
     * 返回匹配实体、邻居实体、关系边和关联 chunkId 集合。
     */
    public GraphSearchResult searchWithExpansion(String query) {
        if (query == null || query.isBlank()) {
            return GraphSearchResult.empty();
        }
        List<GraphEntity> matched = entityRepo.findByNameContainingIgnoreCase(query, PageRequest.of(0, 5));
        if (matched.isEmpty()) {
            return GraphSearchResult.empty();
        }
        Set<String> entityIds = matched.stream().map(GraphEntity::getId).collect(Collectors.toSet());
        List<GraphRelation> edges = new ArrayList<>();
        List<GraphNode> neighbors = new ArrayList<>();
        Set<String> chunkIds = new LinkedHashSet<>();

        for (GraphEntity e : matched) {
            chunkIds.add(e.getSourceChunkId());
            List<GraphRelation> outEdges = relationRepo.findBySourceEntityIdAndActiveTrue(e.getId());
            List<GraphRelation> inEdges = relationRepo.findByTargetEntityIdAndActiveTrue(e.getId());
            edges.addAll(outEdges);
            edges.addAll(inEdges);
            for (GraphRelation r : outEdges) {
                chunkIds.add(r.getSourceChunkId());
                if (neighbors.size() >= maxNeighbors) break;
                entityRepo.findById(r.getTargetEntityId()).filter(GraphEntity::isActive).ifPresent(t -> {
                    if (!entityIds.contains(t.getId())) {
                        neighbors.add(toNode(t));
                    }
                });
            }
            for (GraphRelation r : inEdges) {
                if (neighbors.size() >= maxNeighbors) break;
                entityRepo.findById(r.getSourceEntityId()).filter(GraphEntity::isActive).ifPresent(s -> {
                    if (!entityIds.contains(s.getId())) {
                        neighbors.add(toNode(s));
                    }
                });
            }
            if (neighbors.size() >= maxNeighbors) break;
        }

        List<GraphNode> matchedNodes = matched.stream().map(this::toNode).toList();
        List<GraphEdge> edgeDtos = edges.stream().distinct().map(this::toEdge).toList();
        chunkIds.remove(null);
        return new GraphSearchResult(matchedNodes, neighbors, edgeDtos, chunkIds);
    }

    /** 获取单个实体的详情（含 1-hop 邻居和关系）。 */
    public GraphNode getEntityDetail(String entityId) {
        return entityRepo.findById(entityId).map(this::toNode).orElse(null);
    }

    /** 获取实体的 1-hop 邻居和关系。 */
    public GraphSearchResult getEntityNeighbors(String entityId) {
        var entity = entityRepo.findById(entityId).orElse(null);
        if (entity == null || !entity.isActive()) {
            return GraphSearchResult.empty();
        }
        List<GraphRelation> outEdges = relationRepo.findBySourceEntityIdAndActiveTrue(entityId);
        List<GraphRelation> inEdges = relationRepo.findByTargetEntityIdAndActiveTrue(entityId);
        List<GraphNode> neighbors = new ArrayList<>();
        Set<String> chunkIds = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (GraphRelation r : outEdges) {
            chunkIds.add(r.getSourceChunkId());
            entityRepo.findById(r.getTargetEntityId()).filter(GraphEntity::isActive).ifPresent(t -> {
                if (seen.add(t.getId()) && neighbors.size() < maxNeighbors) {
                    neighbors.add(toNode(t));
                }
            });
        }
        for (GraphRelation r : inEdges) {
            entityRepo.findById(r.getSourceEntityId()).filter(GraphEntity::isActive).ifPresent(s -> {
                if (seen.add(s.getId()) && neighbors.size() < maxNeighbors) {
                    neighbors.add(toNode(s));
                }
            });
        }
        chunkIds.remove(null);
        List<GraphEdge> edgeDtos = new ArrayList<>();
        outEdges.forEach(r -> edgeDtos.add(toEdge(r)));
        inEdges.forEach(r -> edgeDtos.add(toEdge(r)));
        return new GraphSearchResult(List.of(toNode(entity)), neighbors, edgeDtos, chunkIds);
    }

    /** 获取文档的全部实体和关系（文档子图）。 */
    public GraphSearchResult getDocumentGraph(String docId) {
        List<GraphEntity> entities = entityRepo.findBySourceDocIdAndActiveTrue(docId);
        List<GraphRelation> relations = relationRepo.findBySourceDocIdAndActiveTrue(docId);
        Set<String> chunkIds = entities.stream().map(GraphEntity::getSourceChunkId)
                .filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
        chunkIds.addAll(relations.stream().map(GraphRelation::getSourceChunkId)
                .filter(c -> c != null && !c.isBlank()).toList());
        List<GraphNode> nodes = entities.stream().map(this::toNode).toList();
        List<GraphEdge> edges = relations.stream().map(this::toEdge).toList();
        return new GraphSearchResult(nodes, List.of(), edges, chunkIds);
    }

    /** 全局图谱统计。 */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("entityCount", entityRepo.countByActiveTrue());
        stats.put("relationCount", relationRepo.countByActiveTrue());
        stats.put("maxNeighbors", maxNeighbors);
        return stats;
    }

    /**
     * 分页浏览有效实体（知识图谱页面"已有实体"清单）。
     * type 非空时按类型过滤；页码从 0 开始，size 钳制在 1..100。
     */
    public EntityPage listEntities(int page, int size, String type) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        boolean filterByType = type != null && !type.isBlank();
        Page<GraphEntity> resultPage = filterByType
                ? entityRepo.findByTypeAndActiveTrueOrderByCreatedAtDesc(
                        type.trim(), PageRequest.of(safePage, safeSize))
                : entityRepo.findByActiveTrueOrderByCreatedAtDesc(
                        PageRequest.of(safePage, safeSize));
        List<EntityListRow> rows = resultPage.getContent().stream()
                .map(e -> new EntityListRow(e.getId(), e.getName(), e.getType(), e.getDescription(),
                        e.getSourceDocId(), e.getSourceChunkId(), e.getCreatedAt()))
                .toList();
        return new EntityPage(rows, resultPage.getTotalElements(), safePage, safeSize,
                entityRepo.findDistinctActiveTypes());
    }

    /** 转换为领域节点。 */
    private GraphNode toNode(GraphEntity e) {
        return new GraphNode(e.getId(), e.getName(), e.getType(), e.getDescription(),
                e.getSourceDocId(), e.getSourceChunkId());
    }

    /** 转换为领域边。 */
    private GraphEdge toEdge(GraphRelation r) {
        return new GraphEdge(r.getId(), r.getSourceEntityId(), r.getTargetEntityId(),
                r.getRelationType(), r.getDescription(), r.getWeight(),
                r.getSourceDocId(), r.getSourceChunkId());
    }
}
