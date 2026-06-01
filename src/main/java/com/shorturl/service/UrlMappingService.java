package com.shorturl.service;

import com.shorturl.model.UrlMapping;
import com.shorturl.repository.UrlMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 短链接核心业务逻辑。
 * 负责长短链接转换、短码生成与唯一性校验、URL 安全校验、访问计数与软删除。
 */
@Service
public class UrlMappingService {

    // ==================== 短码生成相关常量 ====================
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;

    // ==================== URL 安全校验常量 ====================
    /** 被禁止的危险协议 scheme */
    private static final Set<String> BLOCKED_SCHEMES = Set.of(
            "javascript", "data", "file", "vbscript", "about"
    );

    /** URL 最大长度，防止 DoS 和数据库截断 */
    private static final int MAX_URL_LENGTH = 2000;

    /** 禁止的内网 IP 段前缀（用于 DNS 重绑定 / SSRF 防护） */
    private static final java.util.regex.Pattern PRIVATE_IP_PATTERN = java.util.regex.Pattern.compile(
            "https?://(127\\.|0\\.0\\.0\\.0|localhost" +
            "|10\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.|192\\.168\\." +
            "|169\\.254\\.|100\\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\\.)", // 100.64.0.0/10 CGNAT
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    // ==================== 依赖 ====================
    private final SecureRandom random = new SecureRandom();
    private final UrlMappingRepository repository;

    public UrlMappingService(UrlMappingRepository repository) {
        this.repository = repository;
    }

    // ==================== 核心业务方法 ====================

    /**
     * 创建短链接。
     * 先规范化并校验原始 URL，若已存在相同长链接则直接复用已有短码，
     * 否则生成新的 6 位唯一短码并持久化。
     *
     * @param originalUrl 原始长链接
     * @param createdBy   创建者标识（anonymous 兜底）
     * @return 创建的或已有的 UrlMapping
     */
    @Transactional
    public UrlMapping createShortUrl(String originalUrl, String createdBy) {
        String normalizedUrl = normalizeUrl(originalUrl);

        // 检查是否已存在该长链接，存在则复用
        Optional<UrlMapping> existing = repository.findByOriginalUrl(normalizedUrl);
        if (existing.isPresent()) {
            return existing.get();
        }

        String shortCode = generateUniqueShortCode();
        UrlMapping mapping = new UrlMapping(shortCode, normalizedUrl);
        mapping.setCreatedBy(createdBy != null ? createdBy : "anonymous");
        return repository.save(mapping);
    }

    /**
     * 根据短码查找并原子递增访问计数（用于重定向）。
     * 先执行 UPDATE，若受影响行数为 0 则记录不存在或已删除；
     * 否则重新 SELECT 返回最新数据。此方式可消除 SELECT-then-UPDATE 的竞态条件。
     *
     * @param shortCode 短码
     * @return 访问计数更新后的 UrlMapping，不存在时返回空
     */
    @Transactional
    public Optional<UrlMapping> getAndIncrementAccess(String shortCode) {
        int updated = repository.incrementAccessCount(shortCode);
        if (updated == 0) {
            return Optional.empty();
        }
        return repository.findByShortCodeAndDeletedFalse(shortCode);
    }

    /**
     * 仅查询统计信息，不增加访问计数。
     *
     * @param shortCode 短码
     * @return UrlMapping，不存在则空
     */
    public Optional<UrlMapping> getStats(String shortCode) {
        return repository.findByShortCodeAndDeletedFalse(shortCode);
    }

    // ==================== 软删除 ====================

    /**
     * 软删除单条短链接（设置 deleted = true）。
     *
     * @param shortCode 短码
     * @return 是否删除成功
     */
    @Transactional
    public boolean softDelete(String shortCode) {
        Optional<UrlMapping> result = repository.findByShortCodeAndDeletedFalse(shortCode);
        if (result.isPresent()) {
            UrlMapping mapping = result.get();
            mapping.setDeleted(true);
            repository.save(mapping);
            return true;
        }
        return false;
    }

    /**
     * 批量软删除多条短链接。
     *
     * @param shortCodes 短码集合
     * @return 实际删除的条数
     */
    @Transactional
    public int batchSoftDelete(Collection<String> shortCodes) {
        if (shortCodes == null || shortCodes.isEmpty()) {
            return 0;
        }
        return repository.batchSoftDelete(shortCodes);
    }

    // ==================== 查询方法 ====================

    /**
     * 查询某用户创建的未删除短链接历史（按创建时间倒序）。
     *
     * @param createdBy 创建者标识
     * @return 短链接列表
     */
    public List<UrlMapping> getUserHistory(String createdBy) {
        return repository.findByCreatedByAndDeletedFalseOrderByCreatedAtDesc(createdBy);
    }

    /**
     * 查询所有未删除的短链接（管理员用）。
     *
     * @return 全部活跃短链接
     */
    public List<UrlMapping> getAllActive() {
        return repository.findAllByDeletedFalseOrderByCreatedAtDesc();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 规范化并校验 URL：
     * 1. 拒绝 null / blank
     * 2. 长度限制（防 DoS）
     * 3. 校验 scheme：仅放行 http / https，拒绝危险协议
     * 4. 无 scheme 时自动补全 https://
     * 5. 统一 scheme 小写以避免重复
     *
     * @param url 用户输入的原始 URL
     * @return 规范化后的 URL
     * @throws RuntimeException 校验不通过时抛出
     */
    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new RuntimeException("URL不能为空");
        }

        String trimmed = url.trim();

        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new RuntimeException("URL长度不能超过" + MAX_URL_LENGTH + "个字符");
        }

        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0) {
            String scheme = trimmed.substring(0, colonIdx).toLowerCase(Locale.ROOT);
            boolean isUrlScheme = (colonIdx + 2 < trimmed.length())
                    && trimmed.charAt(colonIdx + 1) == '/'
                    && trimmed.charAt(colonIdx + 2) == '/';

            if (isUrlScheme) {
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    throw new RuntimeException("仅支持 HTTP/HTTPS URL");
                }
            } else {
                if (BLOCKED_SCHEMES.contains(scheme)) {
                    throw new RuntimeException("不允许的URL协议: " + scheme);
                }
                throw new RuntimeException("仅支持 HTTP/HTTPS URL");
            }
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        } else if (lower.startsWith("http://")) {
            trimmed = "http://" + trimmed.substring(7);
        } else {
            trimmed = "https://" + trimmed.substring(8);
        }
        lower = trimmed.toLowerCase(Locale.ROOT);

        // 禁止内网 IP / localhost（防 DNS 重绑定 / SSRF）
        if (PRIVATE_IP_PATTERN.matcher(lower).find()) {
            throw new RuntimeException("不允许使用内网地址或 localhost");
        }

        return trimmed;
    }

    /**
     * 生成唯一短码，带重试机制（最多尝试 10 次）。
     *
     * @return 6 位唯一短码
     * @throws RuntimeException 重试耗尽时抛出
     */
    private String generateUniqueShortCode() {
        int maxAttempts = 100;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String code = generateRandomCode();
            if (repository.findByShortCodeAndDeletedFalse(code).isEmpty()) {
                return code;
            }
        }
        throw new RuntimeException("无法生成唯一短码，请稍后重试");
    }

    /**
     * 使用 SecureRandom 从 62 个字符中随机选取 6 位生成短码。
     *
     * @return 6 位随机短码
     */
    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
