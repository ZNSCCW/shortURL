package com.shorturl.service;

import com.shorturl.model.UrlMapping;
import com.shorturl.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UrlMappingService 集成测试（@DataJpaTest + 内嵌 H2 内存库）。
 * 验证真实 JPA 持久化下的完整生命周期：创建 → 复用 → 计数 → 历史 → 软删除。
 */
@DataJpaTest
@Import(UrlMappingService.class)
class UrlMappingServiceIntegrationTest {

    @Autowired
    private UrlMappingService service;

    @Autowired
    private UrlMappingRepository repository;

    @Test
    void fullLifecycle_create_reuse_increment_softDelete() {
        // 1. 创建
        UrlMapping created = service.createShortUrl("https://www.example.com/very/long/path?q=1", "user-1");
        assertThat(created.getShortCode()).hasSize(6);
        assertThat(created.getAccessCount()).isZero();
        assertThat(repository.count()).isEqualTo(1);

        // 2. 相同长链接自动复用
        UrlMapping again = service.createShortUrl("https://www.example.com/very/long/path?q=1", "user-1");
        assertThat(again.getId()).isEqualTo(created.getId());
        assertThat(repository.count()).isEqualTo(1);

        // 3. 多次重定向，计数原子递增
        service.getAndIncrementAccess(created.getShortCode());
        service.getAndIncrementAccess(created.getShortCode());
        Optional<UrlMapping> after = service.getAndIncrementAccess(created.getShortCode());
        assertThat(after).isPresent();
        assertThat(after.get().getAccessCount()).isEqualTo(3L);

        // 4. 统计查询不递增计数
        Optional<UrlMapping> stats = service.getStats(created.getShortCode());
        assertThat(stats).isPresent();
        assertThat(stats.get().getAccessCount()).isEqualTo(3L);

        // 5. 用户历史按创建者查询
        List<UrlMapping> history = service.getUserHistory("user-1");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getShortCode()).isEqualTo(created.getShortCode());

        // 6. 软删除后不可统计、不可重定向、不可再次删除
        assertThat(service.softDelete(created.getShortCode())).isTrue();
        assertThat(service.getStats(created.getShortCode())).isEmpty();
        assertThat(service.getAndIncrementAccess(created.getShortCode())).isEmpty();
        assertThat(service.softDelete(created.getShortCode())).isFalse();
        assertThat(service.getAllActive()).isEmpty();
    }

    @Test
    void createShortUrl_shouldGenerateDistinctCodesForDifferentUrls() {
        UrlMapping a = service.createShortUrl("https://a.example.com", "u");
        UrlMapping b = service.createShortUrl("https://b.example.com", "u");

        assertThat(a.getShortCode()).isNotEqualTo(b.getShortCode());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void batchSoftDelete_shouldDeleteMultipleInOneCall() {
        UrlMapping a = service.createShortUrl("https://a.example.com", "u");
        UrlMapping b = service.createShortUrl("https://b.example.com", "u");
        UrlMapping c = service.createShortUrl("https://c.example.com", "u");

        int deleted = service.batchSoftDelete(List.of(a.getShortCode(), b.getShortCode()));

        assertThat(deleted).isEqualTo(2);
        assertThat(service.getAllActive()).extracting(UrlMapping::getShortCode)
                .containsExactly(c.getShortCode());
    }

    @Test
    void userHistory_shouldBeIsolatedPerCreator() {
        service.createShortUrl("https://a.example.com", "user-A");
        service.createShortUrl("https://b.example.com", "user-B");

        assertThat(service.getUserHistory("user-A")).hasSize(1);
        assertThat(service.getUserHistory("user-B")).hasSize(1);
        assertThat(service.getUserHistory("user-C")).isEmpty();
    }
}
