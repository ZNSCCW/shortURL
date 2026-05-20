package com.shorturl.model;

/**
 * 仅用于 Session 存储的轻量 DTO，不包含密码哈希。
 */
public class SessionUser {

    private final Long id;
    private final String username;
    private final String role;
    private final boolean enabled;

    public SessionUser(AdminUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.role = user.getRole();
        this.enabled = user.isEnabled();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }
}