package com.wikiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "kb_child_chunk")
public class KbChildChunk {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "parent_id", nullable = false, length = 64)
    private String parentId;

    @Column(name = "child_index", nullable = false)
    private int childIndex;

    /** C2：来源页码（富解析分页可溯源；纯文本/旧版解析为 null）。 */
    @Column(name = "page_no")
    private Integer pageNo;

    /** 修复4：所属文档版本（重解析新版本产生新 chunk 行）。 */
    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    /** 修复4：是否当前有效（重解析后旧版本 chunk 置 false 保留，不物理删除）。 */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(nullable = false, length = 4000)
    private String content;

    /** V19：chunk 内容哈希（去重检测用；nullable，存量行回填 null）。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public int getChildIndex() { return childIndex; }
    public void setChildIndex(int childIndex) { this.childIndex = childIndex; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
