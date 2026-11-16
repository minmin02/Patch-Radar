package io.patchradar.collector.publish;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.patchradar.collector.source.Release;
import tools.jackson.databind.json.JsonMapper;

/** release.detected 스키마대로 Redis Stream에 발행. */
@Component
public class Publisher {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;
    private final String streamKey;

    public Publisher(StringRedisTemplate redis, @Value("${patchradar.stream.key:release.detected}") String streamKey) {
        this.redis = redis;
        this.streamKey = streamKey;
    }

    public void publish(Release r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", UUID.randomUUID().toString());
        m.put("occurredAt", Instant.now().toString()); // UTC, 'Z'
        m.put("tool", r.tool());
        m.put("releaseKey", r.releaseKey());
        m.put("version", r.version());
        m.put("title", r.title());
        m.put("url", r.url());
        m.put("publishedAt", r.publishedAt().toString());
        m.put("body", r.body());
        m.put("bodyFormat", r.bodyFormat());
        m.put("security", r.security());
        m.put("source", r.source());
        redis.opsForStream().add(StreamRecords.string(Map.of("payload", JSON.writeValueAsString(m)))
                .withStreamKey(streamKey));
    }
}
