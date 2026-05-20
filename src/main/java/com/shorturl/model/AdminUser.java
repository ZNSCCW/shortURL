package com.shorturl.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 管理员实体。
 * 存储用户名、BCrypt 密码哈希、角色（SUPER_ADMIN / ADMIN）及启用状态。
 */
@Entity
@Table(name = "admin_user")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录用户名，全局唯一 */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 哈希后的密码，不可逆 */
    @Column(nullable = false)
    private String passwordHash;

    /** 角色：SUPER_ADMIN（可管理其他管理员）或 ADMIN（仅管理短链接） */
    @Column(nullable = false, length = 20)
    private String role;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 是否启用，禁用后无法登录 */
    @Column(nullable = false)
    private boolean enabled = true;

    public AdminUser() {
    }

    public AdminUser(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
    }

    /**
     * 持久化前回调：若创建时间为空则自动设置为当前时间。
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
