package com.shorturl.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 短链接映射实体。
 * 每条记录存储原始长链接、6 位短码、访问计数、创建者标识，以及软删除标记。
 */
@Entity
@Table(name = "url_mapping")
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 6 位唯一短码（大小写字母 + 数字，62^6 组合空间） */
    @Column(nullable = false, unique = true, length = 6)
    private String shortCode;

    /** 原始长链接，TEXT 类型以容纳超长 URL */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    /** 访问次数，每次重定向原子递增 */
    @Column(nullable = false)
    private Long accessCount = 0L;

    /** 创建时间，插入前自动设置 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 创建者的匿名标识（来自 Cookie suid） */
    @Column(length = 50)
    private String createdBy;

    /** 软删除标记：true 表示已删除，短码不可复用 */
    @Column(nullable = false)
    private boolean deleted = false;

    public UrlMapping() {
    }

    public UrlMapping(String shortCode, String originalUrl) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.accessCount = 0L;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 持久化前回调：若创建时间为空则自动补全为当前时间。
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // ==================== Getter / Setter ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public Long getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(Long accessCount) {
        this.accessCount = accessCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
