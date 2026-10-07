package com.wikiagent.infrastructure.business;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 业务文件元数据 DAO。
 */
public interface BusinessFileJpaDao extends JpaRepository<BusinessFileEntity, Long> {

    Optional<BusinessFileEntity> findByFileId(String fileId);

    Optional<BusinessFileEntity> findByMappingFileId(String mappingFileId);

    /** 同用户同类型同内容去重（映射表上传 checksum 去重）。 */
    Optional<BusinessFileEntity> findFirstByUserIdAndFileTypeAndChecksum(
            String userId, String fileType, String checksum);
}
