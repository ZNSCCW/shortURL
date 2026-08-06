package com.shorturl.service;

import com.shorturl.model.UrlMapping;
import com.shorturl.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UrlMappingService 单元测试（Mockito mock Repository，不加载 Spring 上下文）。
 * 覆盖：URL 规范化与安全校验、短码生成、复用、访问计数、软删除。
 */
class UrlMappingServiceTest {

    private UrlMappingRepository repository;
    private UrlMappingService service;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        service = new UrlMappingService(repository);
    }

    // ==================== createShortUrl ====================

    @Test
    void createShortUrl_shouldPrependHttpsWhenSchemeMissing() {
        when(repository.findByOriginalUrlAndDeletedFalse("https://example.com/abc")).thenReturn(Optional.empty());
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.createShortUrl("example.com/abc", "user1");

        assertThat(result.getOriginalUrl()).isEqualTo("https://example.com/abc");
        assertThat(result.getShortCode()).hasSize(6);
        assertThat(result.getShortCode()).matches("^[a-zA-Z0-9]{6}$");
        assertThat(result.getCreatedBy()).isEqualTo("user1");
        assertThat(result.isDeleted()).isFalse();
    }

    @Test
    void createShortUrl_shouldNormalizeSchemeCase() {
        when(repository.findByOriginalUrlAndDeletedFalse("https://EXAMPLE.COM")).thenReturn(Optional.empty());
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.createShortUrl("HTTPS://EXAMPLE.COM", "u");

        // 只规范化 scheme 大小写，host 域名保持原样（DNS 大小写不敏感）
        assertThat(result.getOriginalUrl()).isEqualTo("https://EXAMPLE.COM");
    }

    @Test
    void createShortUrl_shouldReuseExistingMappingForSameUrl() {
        UrlMapping existing = new UrlMapping("abc123", "https://example.com");
        when(repository.findByOriginalUrlAndDeletedFalse("https://example.com")).thenReturn(Optional.of(existing));

        UrlMapping result = service.createShortUrl("example.com", "u");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void createShortUrl_shouldRetryWhenShortCodeCollides() {
        when(repository.findByOriginalUrlAndDeletedFalse("https://example.com")).thenReturn(Optional.empty());
        // 第一次生成的短码冲突，第二次成功
        when(repository.findByShortCodeAndDeletedFalse(anyString()))
                .thenReturn(Optional.of(new UrlMapping("x1xxxx", "https://other.com")))
                .thenReturn(Optional.empty());
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.createShortUrl("https://example.com", "u");

        assertThat(result.getShortCode()).hasSize(6);
        verify(repository, times(2)).findByShortCodeAndDeletedFalse(anyString());
    }

    @Test
    void createShortUrl_shouldDefaultCreatedByToAnonymous() {
        when(repository.findByOriginalUrlAndDeletedFalse("https://example.com")).thenReturn(Optional.empty());
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        UrlMapping result = service.createShortUrl("https://example.com", null);

        assertThat(result.getCreatedBy()).isEqualTo("anonymous");
    }

    // ==================== URL 安全校验 ====================

    @Test
    void createShortUrl_shouldRejectBlankUrl() {
        assertThatThrownBy(() -> service.createShortUrl("  ", "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URL不能为空");
        assertThatThrownBy(() -> service.createShortUrl(null, "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URL不能为空");
    }

    @Test
    void createShortUrl_shouldRejectDangerousSchemes() {
        // 不带 // 的危险协议（如 javascript:alert(1)）→ 拦截并指明协议
        String[] dangerous = {
                "javascript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD4=",
                "vbscript:msgbox(1)",
                "about:blank",
        };
        for (String url : dangerous) {
            assertThatThrownBy(() -> service.createShortUrl(url, "u"))
                    .as("URL: %s", url)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("不允许的URL协议");
        }
        // 带 // 的危险协议（如 file:///etc/passwd）→ 走 scheme 白名单分支
        assertThatThrownBy(() -> service.createShortUrl("file:///etc/passwd", "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("仅支持 HTTP/HTTPS URL");
    }

    @Test
    void createShortUrl_shouldRejectNonHttpScheme() {
        assertThatThrownBy(() -> service.createShortUrl("ftp://example.com", "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("仅支持 HTTP/HTTPS");
        assertThatThrownBy(() -> service.createShortUrl("mailto:test@example.com", "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("仅支持 HTTP/HTTPS");
    }

    @Test
    void createShortUrl_shouldRejectPrivateIpAddresses() {
        String[] privateUrls = {
                "http://127.0.0.1/admin",
                "http://localhost:8080",
                "http://10.0.0.1/x",
                "http://192.168.1.1",
                "http://172.16.0.1",
                "http://169.254.169.254/latest/meta-data",
        };
        for (String url : privateUrls) {
            assertThatThrownBy(() -> service.createShortUrl(url, "u"))
                    .as("URL: %s", url)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("不允许使用内网地址");
        }
    }

    @Test
    void createShortUrl_shouldRejectOverlongUrl() {
        String longUrl = "https://example.com/" + "a".repeat(2000);
        assertThatThrownBy(() -> service.createShortUrl(longUrl, "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("URL长度不能超过");
    }

    // ==================== getAndIncrementAccess ====================

    @Test
    void getAndIncrementAccess_shouldReturnMappingWithLatestCount() {
        UrlMapping mapping = new UrlMapping("abc123", "https://example.com");
        mapping.setAccessCount(5L);
        when(repository.incrementAccessCount("abc123")).thenReturn(1);
        when(repository.findByShortCodeAndDeletedFalse("abc123")).thenReturn(Optional.of(mapping));

        Optional<UrlMapping> result = service.getAndIncrementAccess("abc123");

        assertThat(result).isPresent();
        assertThat(result.get().getAccessCount()).isEqualTo(5L);
        verify(repository).incrementAccessCount("abc123");
    }

    @Test
    void getAndIncrementAccess_shouldReturnEmptyWhenUpdateAffectsZeroRows() {
        when(repository.incrementAccessCount("missing")).thenReturn(0);

        assertThat(service.getAndIncrementAccess("missing")).isEmpty();
        // 更新 0 行时不应再发起查询
        verify(repository, never()).findByShortCodeAndDeletedFalse(anyString());
    }

    // ==================== 统计与软删除 ====================

    @Test
    void getStats_shouldQueryWithoutIncrementing() {
        UrlMapping mapping = new UrlMapping("abc123", "https://example.com");
        when(repository.findByShortCodeAndDeletedFalse("abc123")).thenReturn(Optional.of(mapping));

        Optional<UrlMapping> result = service.getStats("abc123");

        assertThat(result).contains(mapping);
        verify(repository, never()).incrementAccessCount(anyString());
    }

    @Test
    void softDelete_shouldMarkDeletedWhenExists() {
        when(repository.findByShortCodeAndDeletedFalse("abc123"))
                .thenReturn(Optional.of(new UrlMapping("abc123", "https://example.com")));

        assertThat(service.softDelete("abc123")).isTrue();
        verify(repository).save(argThat(UrlMapping::isDeleted));
    }

    @Test
    void softDelete_shouldReturnFalseWhenNotExists() {
        when(repository.findByShortCodeAndDeletedFalse("nope")).thenReturn(Optional.empty());

        assertThat(service.softDelete("nope")).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void batchSoftDelete_shouldDelegateToRepository() {
        when(repository.batchSoftDelete(anyCollection())).thenReturn(2);

        int deleted = service.batchSoftDelete(List.of("a1", "b2"));

        assertThat(deleted).isEqualTo(2);
    }

    @Test
    void batchSoftDelete_shouldReturnZeroForNullOrEmptyInput() {
        assertThat(service.batchSoftDelete(null)).isZero();
        assertThat(service.batchSoftDelete(Collections.emptyList())).isZero();
        verify(repository, never()).batchSoftDelete(any());
    }

    @Test
    void getUserHistory_shouldQueryByCreator() {
        service.getUserHistory("user-1");
        verify(repository).findByCreatedByAndDeletedFalseOrderByCreatedAtDesc("user-1");
    }
}
