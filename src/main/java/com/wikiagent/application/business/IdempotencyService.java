package com.wikiagent.application.business;

import com.wikiagent.infrastructure.business.BusinessIdempotencyEntity;
import com.wikiagent.infrastructure.business.BusinessIdempotencyJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 操作幂等服务（对标 Stripe / IETF Idempotency-Key，独立幂等表 + 唯一约束原子抢占）。
 * <p>
 * 行为矩阵（§5.4）：
 * <ul>
 *   <li>首次 → 插入 IN_PROGRESS，调用方 PROCEED 执行</li>
 *   <li>并发重复（原操作仍在跑）→ 唯一约束冲突 + IN_PROGRESS → {@link IdemDecision#IN_FLIGHT}（409）</li>
 *   <li>同键同载荷重试（已完成）→ {@link IdemDecision#REPLAY}（回放 resultRef）</li>
 *   <li>同键不同载荷 → {@link IdemDecision#CONFLICT}（422，禁止静默返回旧结果）</li>
 *   <li>记录过期 → 删除后重插，允许重新操作</li>
 *   <li>首次 FAILED → 复用该行重新执行</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public enum IdemDecision { PROCEED, REPLAY, CONFLICT, IN_FLIGHT }

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 幂等裁决结果。 */
    public record IdemResult(IdemDecision decision, String resultRef) {
        static IdemResult proceed() { return new IdemResult(IdemDecision.PROCEED, null); }
        static IdemResult replay(String ref) { return new IdemResult(IdemDecision.REPLAY, ref); }
        static IdemResult conflict() { return new IdemResult(IdemDecision.CONFLICT, null); }
        static IdemResult inFlight() { return new IdemResult(IdemDecision.IN_FLIGHT, null); }
    }

    private final BusinessIdempotencyJpaDao dao;
    private final int ttlHours;
    private final Clock clock;
    /**
     * 抢占专用独立事务：唯一约束冲突时仅回滚该事务、丢弃脏 Hibernate Session，
     * 不污染调用方的外层业务事务（冲突后仍可在干净 Session 中裁决既有记录）。
     */
    private final TransactionTemplate claimTx;

    @Autowired
    public IdempotencyService(BusinessIdempotencyJpaDao dao,
                              com.wikiagent.config.BusinessIntentProperties props,
                              PlatformTransactionManager txManager) {
        this.dao = dao;
        this.ttlHours = Math.max(1, props.getIdempotencyTtlHours());
        this.clock = Clock.systemUTC();
        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 抢占幂等键。
     *
     * @return PROCEED（本调用方应执行）、REPLAY/resultRef、CONFLICT、IN_FLIGHT
     */
    @Transactional
    public IdemResult begin(String scope, String idemKey, String requestHash) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expires = now.plusHours(ttlHours);
        try {
            // 独立事务抢占：冲突回滚只影响该事务，外层 Session 保持干净
            claimTx.executeWithoutResult(status -> dao.saveAndFlush(new BusinessIdempotencyEntity(
                    scope, idemKey, requestHash,
                    BusinessIdempotencyEntity.STATUS_IN_PROGRESS, expires)));
            return IdemResult.proceed();
        } catch (DataIntegrityViolationException e) {
            // 唯一约束命中：并发/重试/同键异载荷，进入既有记录裁决
            return resolveExisting(scope, idemKey, requestHash, now);
        }
    }

    private IdemResult resolveExisting(String scope, String idemKey, String requestHash, LocalDateTime now) {
        var existingOpt = dao.findByScopeAndIdemKey(scope, idemKey);
        if (existingOpt.isEmpty()) {
            // 极端并发下已删除，重试一次抢占
            return IdemResult.inFlight();
        }
        BusinessIdempotencyEntity existing = existingOpt.get();

        // 过期：删除后允许重新操作（下次 begin 重新抢占）
        if (existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(now)) {
            dao.delete(existing);
            dao.flush();
            return IdemResult.inFlight();
        }

        return switch (existing.getStatus()) {
            case BusinessIdempotencyEntity.STATUS_IN_PROGRESS -> IdemResult.inFlight();
            case BusinessIdempotencyEntity.STATUS_COMPLETED -> requestHash.equals(existing.getRequestHash())
                    ? IdemResult.replay(existing.getResultRef())
                    : IdemResult.conflict();
            default -> { // FAILED：复用该行重新执行
                existing.setStatus(BusinessIdempotencyEntity.STATUS_IN_PROGRESS);
                existing.setError(null);
                dao.save(existing);
                yield IdemResult.proceed();
            }
        };
    }

    @Transactional
    public void complete(String scope, String idemKey, String resultRef) {
        dao.findByScopeAndIdemKey(scope, idemKey).ifPresent(e -> {
            e.setStatus(BusinessIdempotencyEntity.STATUS_COMPLETED);
            e.setResultRef(resultRef);
            dao.save(e);
        });
    }

    @Transactional
    public void fail(String scope, String idemKey, String error) {
        dao.findByScopeAndIdemKey(scope, idemKey).ifPresent(e -> {
            e.setStatus(BusinessIdempotencyEntity.STATUS_FAILED);
            e.setError(truncate(error, 512));
            dao.save(e);
        });
    }

    /**
     * 每日清理已过期的幂等记录（§5.6）：只清幂等记录，business_file 与物理文件不动，
     * 历史会话下载链接长期有效。运行时遇到过期行也会即时删除（{@code resolveExisting}），
     * 本任务负责批量兜底。
     */
    @Scheduled(cron = "${wikiagent.business-intent.idempotency-purge-cron:0 0 3 * * ?}")
    @Transactional
    public void purgeExpired() {
        int removed = dao.deleteExpired(LocalDateTime.now(clock));
        if (removed > 0) {
            log.info("已清理过期业务幂等记录 {} 条", removed);
        }
    }

    /** 归一化载荷 sha256（字段顺序无关由调用方保证；此处仅做字符串哈希）。 */
    public static String requestHash(String normalizedPayload) {
        return sha256Hex(normalizedPayload == null ? "" : normalizedPayload);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
