package com.shorturl.controller;

import com.shorturl.model.AdminUser;
import com.shorturl.model.SessionUser;
import com.shorturl.model.UrlMapping;
import com.shorturl.service.AdminService;
import com.shorturl.service.UrlMappingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final String SESSION_KEY = "adminUser";

    private final AdminService adminService;
    private final UrlMappingService urlMappingService;

    public AdminController(AdminService adminService, UrlMappingService urlMappingService) {
        this.adminService = adminService;
        this.urlMappingService = urlMappingService;
    }

    // ==================== 权限检查 ====================

    private SessionUser requireSuperAdmin(HttpServletRequest request) {
        SessionUser user = requireAdmin(request);
        if (!"SUPER_ADMIN".equals(user.getRole())) {
            throw new SecurityException("需要超级管理员权限");
        }
        return user;
    }

    private SessionUser requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new SecurityException("未登录");
        }
        SessionUser user = (SessionUser) session.getAttribute(SESSION_KEY);
        if (user == null || !user.isEnabled()) {
            throw new SecurityException("未登录或账号已禁用");
        }
        return user;
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handleSecurity(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    // ==================== URL 管理 ====================

    /**
     * 查看所有短链接（管理员）
     */
    @GetMapping("/urls")
    public ResponseEntity<List<UrlMapping>> getAllUrls(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(urlMappingService.getAllActive());
    }

    /**
     * 软删除短链接
     */
    @DeleteMapping("/urls/{shortCode}")
    public ResponseEntity<Map<String, Object>> deleteUrl(@PathVariable String shortCode,
                                                          HttpServletRequest request) {
        requireAdmin(request);
        boolean deleted = urlMappingService.softDelete(shortCode);
        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "已删除"));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 批量软删除短链接
     */
    @PostMapping("/urls/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDeleteUrls(@RequestBody Map<String, Object> body,
                                                                HttpServletRequest request) {
        requireAdmin(request);
        @SuppressWarnings("unchecked")
        var codesList = (java.util.List<String>) body.get("codes");
        if (codesList == null || codesList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "codes 不能为空"));
        }
        int deleted = urlMappingService.batchSoftDelete(codesList);
        return ResponseEntity.ok(Map.of("message", "已批量删除", "count", deleted));
    }

    // ==================== 管理员管理 ====================

    /**
     * 创建管理员（仅超级管理员）
     */
    @PostMapping("/admins")
    public ResponseEntity<Map<String, Object>> createAdmin(@RequestBody Map<String, String> body,
                                                           HttpServletRequest request) {
        requireSuperAdmin(request);
        String username = body.get("username");
        String password = body.get("password");
        String role = body.get("role");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }

        try {
            AdminUser user = adminService.createAdmin(username, password, role != null ? role : "ADMIN");
            return ResponseEntity.ok(Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "role", user.getRole()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查看所有管理员
     */
    @GetMapping("/admins")
    public ResponseEntity<List<Map<String, Object>>> getAllAdmins(HttpServletRequest request) {
        requireSuperAdmin(request);
        List<AdminUser> admins = adminService.getAllAdmins();
        List<Map<String, Object>> result = admins.stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "username", u.getUsername(),
                        "role", u.getRole(),
                        "enabled", u.isEnabled(),
                        "createdAt", u.getCreatedAt().toString()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 删除管理员
     */
    @DeleteMapping("/admins/{id}")
    public ResponseEntity<Map<String, String>> deleteAdmin(@PathVariable Long id,
                                                           HttpServletRequest request) {
        requireSuperAdmin(request);
        boolean deleted = adminService.deleteAdmin(id);
        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "已删除"));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 禁用/启用管理员
     */
    @PutMapping("/admins/{id}/toggle")
    public ResponseEntity<Map<String, String>> toggleAdmin(@PathVariable Long id,
                                                           HttpServletRequest request) {
        requireSuperAdmin(request);
        boolean toggled = adminService.toggleEnabled(id);
        if (toggled) {
            return ResponseEntity.ok(Map.of("message", "状态已切换"));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 修改密码
     */
    @PutMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody Map<String, String> body,
                                                               HttpServletRequest request) {
        SessionUser user = requireAdmin(request);
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");

        if (oldPassword == null || oldPassword.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码不能为空"));
        }

        boolean changed = adminService.changePassword(user.getUsername(), oldPassword, newPassword);
        if (changed) {
            return ResponseEntity.ok(Map.of("message", "密码修改成功"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "原密码错误"));
    }
}