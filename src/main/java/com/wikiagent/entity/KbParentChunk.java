package com.wikiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "kb_parent_chunk")
public class KbParentChunk {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "doc_id", nullable = false, length = 64)
    private String docId;

    @Column(name = "parent_index", nullable = false)
    private int parentIndex;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public int getParentIndex() { return parentIndex; }
    public void setParentIndex(int parentIndex) { this.parentIndex = parentIndex; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
