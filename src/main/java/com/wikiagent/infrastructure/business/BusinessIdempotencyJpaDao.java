package com.wikiagent.infrastructure.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 操作幂等记录 DAO。
 */
public interface BusinessIdempotencyJpaDao extends JpaRepository<BusinessIdempotencyEntity, Long> {

    Optional<BusinessIdempotencyEntity> findByScopeAndIdemKey(String scope, String idemKey);

    /**
     * 删除已过期的幂等记录（§5.6 每日清理；只清幂等记录，business_file 与物理文件不动）。
     *
     * @return 删除行数
     */
    @Modifying
    @Query("DELETE FROM BusinessIdempotencyEntity e WHERE e.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
