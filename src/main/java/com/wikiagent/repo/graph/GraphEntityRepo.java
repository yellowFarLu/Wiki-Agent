package com.wikiagent.repo.graph;

import com.wikiagent.entity.graph.GraphEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GraphEntityRepo extends JpaRepository<GraphEntity, String> {

    /** 按名称精确匹配（GraphRAG 检索用）。 */
    Optional<GraphEntity> findByNameAndType(String name, String type);

    /** 按名称模糊匹配（检索/前端搜索用）。 */
    List<GraphEntity> findByNameContainingIgnoreCase(String name, Pageable pageable);

    /** 按文档 ID 查询有效实体。 */
    List<GraphEntity> findBySourceDocIdAndActiveTrue(String docId);

    /** 按 ID 集合查询有效实体。 */
    List<GraphEntity> findByIdInAndActiveTrue(Collection<String> ids);

    /** 按类型查询。 */
    List<GraphEntity> findByTypeAndActiveTrue(String type);

    /** 分页浏览全部有效实体（按创建时间倒序）。 */
    Page<GraphEntity> findByActiveTrueOrderByCreatedAtDesc(Pageable pageable);

    /** 按类型分页浏览有效实体（按创建时间倒序）。 */
    Page<GraphEntity> findByTypeAndActiveTrueOrderByCreatedAtDesc(String type, Pageable pageable);

    /** 全部有效实体的类型清单（前端筛选下拉用）。 */
    @Query("select distinct e.type from GraphEntity e where e.active = true order by e.type")
    List<String> findDistinctActiveTypes();

    /** 删除文档时软下线关联实体。 */
    @Modifying
    @Query("update GraphEntity e set e.active = false, e.updatedAt = CURRENT_TIMESTAMP where e.sourceDocId = :docId and e.active = true")
    int deactivateByDocId(@Param("docId") String docId);

    /** 统计有效实体数量。 */
    long countByActiveTrue();
}
