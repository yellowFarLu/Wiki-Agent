package com.wikiagent.interfaces.graph;

import com.wikiagent.application.graph.GraphRagService;
import com.wikiagent.domain.graph.GraphNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GraphRAG REST API：图搜索、实体详情、文档子图、统计。
 * wikiagent.graph.enabled=true 时激活（与 GraphRagService 条件一致）。
 */
@RestController
@RequestMapping("/api/graph")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "wikiagent.graph.enabled", havingValue = "true", matchIfMissing = false)
public class GraphRagController {

    private final GraphRagService graphRagService;

    public GraphRagController(GraphRagService graphRagService) {
        this.graphRagService = graphRagService;
    }

    /** 全局图谱统计。 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(graphRagService.getStats());
    }

    /**
     * 分页浏览有效实体（"已有实体"清单）。
     * page 从 0 开始；type 可选过滤。
     */
    @GetMapping("/entities")
    public ResponseEntity<GraphRagService.EntityPage> entities(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "type", required = false) String type) {
        return ResponseEntity.ok(graphRagService.listEntities(page, size, type));
    }

    /** 图搜索：实体匹配 + 邻居扩展。 */
    @GetMapping("/search")
    public ResponseEntity<GraphRagService.GraphSearchResult> search(@RequestParam String q) {
        return ResponseEntity.ok(graphRagService.searchWithExpansion(q));
    }

    /** 实体详情。 */
    @GetMapping("/entity/{id}")
    public ResponseEntity<GraphNode> entity(@PathVariable String id) {
        GraphNode node = graphRagService.getEntityDetail(id);
        return node == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(node);
    }

    /** 实体邻居（1-hop）。 */
    @GetMapping("/entity/{id}/neighbors")
    public ResponseEntity<GraphRagService.GraphSearchResult> neighbors(@PathVariable String id) {
        return ResponseEntity.ok(graphRagService.getEntityNeighbors(id));
    }

    /** 文档子图。 */
    @GetMapping("/doc/{docId}")
    public ResponseEntity<GraphRagService.GraphSearchResult> docGraph(@PathVariable String docId) {
        return ResponseEntity.ok(graphRagService.getDocumentGraph(docId));
    }
}
