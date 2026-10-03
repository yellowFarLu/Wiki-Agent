package com.wikiagent.repo;

import com.wikiagent.entity.KbChildChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KbChildChunkRepo extends JpaRepository<KbChildChunk, String> {

    List<KbChildChunk> findByDocId(String docId);

    /** 当前文档版本的有效子块（检索/重解析判定用）。 */
    List<KbChildChunk> findByDocIdAndActiveTrue(String docId);

    /** 重解析：软下线旧版本全部子块（保留行做历史）。 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update KbChildChunk c set c.active = false where c.docId = :docId and c.active = true")
    int deactivateByDocId(@org.springframework.data.repository.query.Param("docId") String docId);

    List<KbChildChunk> findByIdIn(Collection<String> ids);

    /** 关键词包含匹配（Milvus 不可用时的本地 BM25 降级用，H2/MySQL 均兼容 LIKE）。 */
    List<KbChildChunk> findByContentContainingIgnoreCase(String keyword, Pageable pageable);

    /** 降级检索的有效版本过滤（修复4：不召回重解析后下线的旧子块）。 */
    List<KbChildChunk> findByContentContainingIgnoreCaseAndActiveTrue(String keyword, Pageable pageable);

    void deleteByDocId(String docId);

    /** L0 精确去重：按规范化内容哈希查首个 active chunk（命中即视为完全重复）。 */
    Optional<KbChildChunk> findFirstByContentHashAndActiveTrue(String contentHash);
}
