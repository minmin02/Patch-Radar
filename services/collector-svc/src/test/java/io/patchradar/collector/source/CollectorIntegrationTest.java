package io.patchradar.collector.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "patchradar.poll.enabled=false")
class CollectorIntegrationTest {

    // release-svc 테스트와 같은 Redis를 쓰므로 키를 테스트마다 고유하게
    static final String KEY = "test.release.detected." + UUID.randomUUID();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("patchradar.stream.key", () -> KEY);
    }

    @Autowired Collector collector;
    @Autowired StringRedisTemplate redis;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient db;

    @Test
    void publishesOnlyNewOrChanged() throws Exception {
        String tool = "k3s";
        // 다른 테스트/실행과 겹치지 않게 releaseKey를 고유하게
        String ver = "9." + UUID.randomUUID().hashCode() % 1000 + ".1";
        Release r = new Release(tool, ver, ver, ver, "https://example.com/" + ver,
                Instant.parse("2026-01-01T00:00:00Z"), "body", "markdown", false, "github");

        assertThat(collector.publishChanged(List.of(r))).isEqualTo(1);
        assertThat(collector.publishChanged(List.of(r))).isZero();

        Release changed = new Release(tool, ver, ver, ver, r.url(), r.publishedAt(), "new body", "markdown", false, "github");
        assertThat(collector.publishChanged(List.of(changed))).isEqualTo(1);

        var records = redis.opsForStream().range(KEY, org.springframework.data.domain.Range.unbounded());
        assertThat(records).hasSize(2);
        JsonNode e = new JsonMapper().readTree((String) records.get(1).getValue().get("payload"));
        assertThat(e.propertyNames()).containsExactlyInAnyOrder("eventId", "occurredAt", "tool", "releaseKey",
                "version", "title", "url", "publishedAt", "body", "bodyFormat", "security", "source");
        assertThat(e.get("body").asString()).isEqualTo("new body");
        assertThat(e.get("publishedAt").asString()).isEqualTo("2026-01-01T00:00:00Z");
        db.sql("delete from published_release where tool = ? and release_key = ?").params(tool, ver).update();
        redis.delete(KEY);
    }

    @Test
    void parsedSampleRoundTrip() throws Exception {
        var releases = Parser.rss("jenkins", Files.readString(Path.of("src/test/resources/feeds/jenkins.xml")));
        assertThat(collector.publishChanged(releases)).isLessThanOrEqualTo(releases.size());
        // 실제 피드 키가 이력에 남지 않게 정리
        releases.forEach(r -> db.sql("delete from published_release where tool = ? and release_key = ?")
                .params(r.tool(), r.releaseKey()).update());
        redis.delete(KEY);
    }
}
