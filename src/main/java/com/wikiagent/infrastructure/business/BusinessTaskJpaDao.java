package com.wikiagent.infrastructure.business;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 业务意图任务 DAO。
 */
public interface BusinessTaskJpaDao extends JpaRepository<BusinessTaskEntity, Long> {

    Optional<BusinessTaskEntity> findByBizTaskId(String bizTaskId);

    List<BusinessTaskEntity> findByUserIdAndSessionIdOrderByCreatedAtDesc(String userId, String sessionId);

    List<BusinessTaskEntity> findByOrderNoOrderByCreatedAtDesc(String orderNo);
}
