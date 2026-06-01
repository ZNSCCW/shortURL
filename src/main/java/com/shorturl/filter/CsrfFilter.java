package com.shorturl.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CSRF 防护过滤器（双提交 Cookie 模式）。
 * 为 /api/auth/** 和 /api/admin/** 的非 GET 请求强制校验 CSRF Token。
 * 公开端点（/api/shorten、/api/stats、/api/history）不校验。
 */
@Component
public class CsrfFilter implements Filter {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();

        // 仅处理 /api/ 请求
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String method = request.getMethod().toUpperCase();

        // 需要保护的端点前缀
        boolean isProtectedPath = path.startsWith("/api/auth/") || path.startsWith("/api/admin/");

        // 对受保护端点的非 GET/HEAD/OPTIONS 请求进行 CSRF 校验
        if (isProtectedPath && ("POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method))) {

            String cookieToken = getCookieValue(request, CSRF_COOKIE);
            String headerToken = request.getHeader(CSRF_HEADER);

            if (cookieToken == null || headerToken == null || !cookieToken.equals(headerToken)) {
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("{\"error\":\"CSRF 校验失败，请刷新页面后重试\"}");
                return;
            }
        }

        // 确保持久化 CSRF Cookie（不存在则生成，存在则续期）
        String existingToken = getCookieValue(request, CSRF_COOKIE);
        if (existingToken == null || existingToken.isBlank()) {
            existingToken = generateToken();
        }
        setCsrfCookie(response, existingToken, request);

        chain.doFilter(request, response);
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (name.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void setCsrfCookie(HttpServletResponse response, String token, HttpServletRequest request) {
        Cookie cookie = new Cookie(CSRF_COOKIE, token);
        cookie.setHttpOnly(false); // JS 需要能读取此 Cookie
        cookie.setSecure("https".equals(request.getScheme()));
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        // 不设 MaxAge = Session Cookie，但每次请求都会刷新
        response.addCookie(cookie);
    }
}