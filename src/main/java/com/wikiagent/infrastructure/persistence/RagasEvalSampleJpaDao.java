package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** RAGAS 评测用例集 DAO。 */
public interface RagasEvalSampleJpaDao extends JpaRepository<RagasEvalSampleEntity, Long> {

    List<RagasEvalSampleEntity> findByRunIdOrderByIdAsc(String runId);
}
