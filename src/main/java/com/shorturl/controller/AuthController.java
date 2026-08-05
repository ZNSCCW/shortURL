package com.shorturl.controller;

import com.shorturl.model.AdminUser;
import com.shorturl.model.SessionUser;
import com.shorturl.service.AdminService;
import com.shorturl.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_KEY = "adminUser";

    private final AdminService adminService;
    private final LoginRateLimiter rateLimiter;

    @Value("${shorturl.trusted-proxy:false}")
    private boolean trustedProxy;

    public AuthController(AdminService adminService, LoginRateLimiter rateLimiter) {
        this.adminService = adminService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 管理员登录
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");

        String clientIp = getClientIp(request);

        // 检查是否被速率限制封锁
        if (rateLimiter.isBlocked(clientIp)) {
            return ResponseEntity.status(429)
                    .body(Map.of("error", "登录尝试过于频繁，请5分钟后再试"));
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "用户名和密码不能为空"));
        }

        var result = adminService.authenticate(username, password);
        if (result.isPresent()) {
            AdminUser user = result.get();
            // 防止 Session 固定攻击：先注销旧 Session
            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) {
                oldSession.invalidate();
            }
            // 创建新 Session
            HttpSession session = request.getSession(true);
            // 只存不含密码的轻量 DTO
            session.setAttribute(SESSION_KEY, new SessionUser(user));
            session.setMaxInactiveInterval(-1);

            // 登录成功，清除失败记录
            rateLimiter.clearOnSuccess(clientIp);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "username", user.getUsername(),
                    "role", user.getRole()
            ));
        }

        // 记录登录失败
        rateLimiter.recordFailure(clientIp);

        return ResponseEntity.status(401)
                .body(Map.of("error", "用户名或密码错误"));
    }

    /**
     * 获取客户端真实 IP（支持反向代理）
     */
    private String getClientIp(HttpServletRequest request) {
        if (trustedProxy) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                return xForwardedFor.split(",")[0].strip();
            }
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank()) {
                return xRealIp.strip();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 获取当前登录状态
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            SessionUser user = (SessionUser) session.getAttribute(SESSION_KEY);
            if (user != null && user.isEnabled()) {
                return ResponseEntity.ok(Map.of(
                        "loggedIn", true,
                        "username", user.getUsername(),
                        "role", user.getRole()
                ));
            }
        }
        return ResponseEntity.ok(Map.of("loggedIn", false));
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }
}
