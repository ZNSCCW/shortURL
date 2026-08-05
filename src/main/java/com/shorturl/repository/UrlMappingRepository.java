package com.shorturl.repository;

import com.shorturl.model.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 短链接映射数据访问层。
 */
@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /** 根据短码查找未删除记录 */
    Optional<UrlMapping> findByShortCodeAndDeletedFalse(String shortCode);

    /** 根据原始长链接查找（用于复用检测） */
    Optional<UrlMapping> findByOriginalUrl(String originalUrl);

    /** 查询某用户创建的未删除短链接，按创建时间倒序 */
    List<UrlMapping> findByCreatedByAndDeletedFalseOrderByCreatedAtDesc(String createdBy);

    /** 查询所有未删除的短链接，按创建时间倒序 */
    List<UrlMapping> findAllByDeletedFalseOrderByCreatedAtDesc();

    /** 原子递增访问计数（UPDATE，非 SELECT-then-UPDATE），返回受影响行数 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UrlMapping m SET m.accessCount = m.accessCount + 1 WHERE m.shortCode = :code AND m.deleted = false")
    int incrementAccessCount(@Param("code") String shortCode);

    /** 批量软删除（设置 deleted = true），返回受影响行数 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UrlMapping m SET m.deleted = true WHERE m.shortCode IN :codes AND m.deleted = false")
    int batchSoftDelete(@Param("codes") Collection<String> shortCodes);
}
