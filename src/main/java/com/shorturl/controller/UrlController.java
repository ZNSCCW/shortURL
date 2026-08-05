package com.shorturl.controller;

import com.shorturl.model.UrlMapping;
import com.shorturl.service.UrlMappingService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class UrlController {

    private static final Logger log = LoggerFactory.getLogger(UrlController.class);

    private static final String USER_ID_COOKIE = "suid";

    private final UrlMappingService service;

    /**
     * 是否信任反向代理头部。开发环境 false，生产环境在 application-prod.yml 中设为 true。
     */
    @Value("${shorturl.trusted-proxy:false}")
    private boolean trustedProxy;

    public UrlController(UrlMappingService service) {
        this.service = service;
    }

    /**
     * API：创建短链接
     */
    @PostMapping("/api/shorten")
    public ResponseEntity<Map<String, Object>> shorten(@RequestBody Map<String, String> body,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        String originalUrl = body.get("url");
        if (originalUrl == null || originalUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "URL不能为空"));
        }

        try {
            String userId = getOrCreateUserId(request, response);
            UrlMapping mapping = service.createShortUrl(originalUrl, userId);
            String baseUrl = getBaseUrl(request);
            String shortUrl = baseUrl + "/s/" + mapping.getShortCode();

            return ResponseEntity.ok(Map.of(
                    "shortUrl", shortUrl,
                    "shortCode", mapping.getShortCode(),
                    "originalUrl", mapping.getOriginalUrl(),
                    "accessCount", mapping.getAccessCount()
            ));
        } catch (Exception e) {
            log.error("创建短链接失败: url={}", originalUrl, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "服务器内部错误，请稍后重试"));
        }
    }

    /**
     * API：查询统计信息（不增加计数）。支持短码或完整短链接。
     * 用法：GET /api/stats?code=abc123 或 GET /api/stats?code=http://localhost:8080/s/abc123
     */
    @GetMapping("/api/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestParam("code") String input) {
        String code = extractShortCode(input);
        Optional<UrlMapping> result = service.getStats(code);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UrlMapping m = result.get();

        return ResponseEntity.ok(Map.of(
                "shortCode", m.getShortCode(),
                "originalUrl", m.getOriginalUrl(),
                "accessCount", m.getAccessCount(),
                "createdAt", m.getCreatedAt().toString()
        ));
    }

    /**
     * 短链接重定向（302），访问计数+1
     */
    @GetMapping("/s/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        Optional<UrlMapping> result = service.getAndIncrementAccess(shortCode);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        UrlMapping mapping = result.get();
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(mapping.getOriginalUrl()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /**
     * 获取当前用户的短链接历史记录
     */
    @GetMapping("/api/history")
    public ResponseEntity<?> history(HttpServletRequest request, HttpServletResponse response) {
        String userId = getOrCreateUserId(request, response);
        return ResponseEntity.ok(service.getUserHistory(userId));
    }

    private String getOrCreateUserId(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (USER_ID_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        String newId = UUID.randomUUID().toString();
        Cookie cookie = new Cookie(USER_ID_COOKIE, newId);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 生产环境应由 nginx 提供 HTTPS 再开启
        cookie.setPath("/");
        cookie.setMaxAge(365 * 24 * 60 * 60); // 1 year
        // 按请求的 scheme 设置 SameSite：HTTPS 用 None，否则用 Lax
        cookie.setAttribute("SameSite", "https".equals(request.getScheme()) ? "None" : "Lax");
        response.addCookie(cookie);
        return newId;
    }

    /**
     * 从输入中提取短码。支持：
     *   - 完整短链接：http://localhost:8080/s/abc123
     *   - 末尾路径：/s/abc123
     *   - 直接短码：abc123
     */
    private String extractShortCode(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        // 先做 URL 解码，防止前端 encodeURIComponent 把 / 编码为 %2F
        try {
            input = URLDecoder.decode(input, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 解码失败就用原值
        }
        String trimmed = input.trim();
        int slashIdx = trimmed.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < trimmed.length() - 1) {
            trimmed = trimmed.substring(slashIdx + 1);
        }
        int qmIdx = trimmed.indexOf('?');
        if (qmIdx >= 0) {
            trimmed = trimmed.substring(0, qmIdx);
        }
        return trimmed;
    }

    private String getBaseUrl(HttpServletRequest request) {
        String scheme;
        String host;
        int port;

        if (trustedProxy) {
            // 信任反向代理头部（仅生产环境）
            scheme = request.getHeader("X-Forwarded-Proto");
            if (scheme == null || scheme.isBlank()) {
                scheme = request.getScheme();
            }
            host = request.getHeader("X-Forwarded-Host");
            if (host == null || host.isBlank()) {
                host = request.getServerName();
            }
            String portHeader = request.getHeader("X-Forwarded-Port");
            if (portHeader != null && !portHeader.isBlank()) {
                try {
                    port = Integer.parseInt(portHeader);
                } catch (NumberFormatException e) {
                    log.warn("无效的 X-Forwarded-Port 值: {}", portHeader);
                    port = request.getServerPort();
                }
            } else {
                port = request.getServerPort();
            }
        } else {
            // 开发/内网环境，不信任代理头部
            scheme = request.getScheme();
            host = request.getServerName();
            port = request.getServerPort();
        }

        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}