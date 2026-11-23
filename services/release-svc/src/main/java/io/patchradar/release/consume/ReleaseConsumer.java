package io.patchradar.release.consume;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.patchradar.release.api.SseHub;
import io.patchradar.release.store.ReleaseStore;
import io.patchradar.release.store.Tools;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ReleaseConsumer {
    private static final Logger log = LoggerFactory.getLogger(ReleaseConsumer.class);
    private static final String GROUP = "release-svc";
    private static final Consumer CONSUMER = Consumer.from(GROUP, "release-svc-1");

    private final StringRedisTemplate redis;
    private final ReleaseStore store;
    private final SseHub hub;
    private final JsonMapper json;
    private final String key;
    private static final int MAX_FAILURES = 5;
    private static final long DEAD_MAXLEN = 10000;
    // ponytail: 메모리 카운트라 재시작하면 0부터 다시 센다
    private final Map<String, Integer> failures = new java.util.HashMap<>();
    private volatile boolean running = true;
    private Thread thread;

    public ReleaseConsumer(StringRedisTemplate redis, ReleaseStore store, SseHub hub, JsonMapper json,
                           @Value("${patchradar.stream.key:release.detected}") String key) {
        this.redis = redis;
        this.store = store;
        this.hub = hub;
        this.json = json;
        this.key = key;
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::loop, "release-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                redis.opsForStream().createGroup(key, ReadOffset.from("0"), GROUP); // MKSTREAM 포함
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                        && !String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                    log.warn("consumer group setup failed, retrying: {}", e.toString());
                    sleep();
                    continue;
                }
            }
            break;
        }
        // 매 반복마다 자기 pending(0)을 먼저 비우고, 없으면 새 메시지(>)를 기다린다.
        // 일시 실패한 메시지는 ACK되지 않아 다음 반복에서 다시 처리된다.
        while (running) {
            try {
                var pending = read(ReadOffset.from("0"), StreamReadOptions.empty().count(50));
                var batch = pending.isEmpty()
                        ? read(ReadOffset.lastConsumed(), StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)))
                        : pending;
                for (var record : batch) {
                    handle(record);
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("consume failed, retrying: {}", e.toString());
                    sleep();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> read(ReadOffset offset, StreamReadOptions options) {
        List<MapRecord<String, Object, Object>> r =
                redis.opsForStream().read(CONSUMER, options, StreamOffset.create(key, offset));
        return r == null ? List.of() : r;
    }

    private void handle(MapRecord<String, Object, Object> record) {
        Parsed p;
        try {
            p = parse(record.getValue());
        } catch (RuntimeException e) { // 독 메시지: 로그 남기고 ACK
            log.error("dropping invalid message {}: {}", record.getId(), e.toString());
            redis.opsForStream().acknowledge(key, GROUP, record.getId());
            return;
        }
        ReleaseStore.Upserted result;
        try {
            result = store.upsert(p.tool, p.releaseKey, p.version, p.title, p.url, p.publishedAt, p.body,
                    p.bodyFormat, p.security, p.source);
        } catch (RuntimeException e) { // ACK 없이 전파. 영구 오류만 세고 MAX_FAILURES회째면 dead stream으로 이동
            if (!(e instanceof NonTransientDataAccessException)) {
                throw e; // 일시적 오류(연결 실패 등)는 세지 않고 계속 재시도
            }
            String id = record.getId().getValue();
            if (failures.merge(id, 1, Integer::sum) < MAX_FAILURES) {
                throw e;
            }
            log.error("moving message {} to {}.dead after {} failures: {}", id, key, MAX_FAILURES, e.toString());
            var dead = redis.opsForStream().add(StreamRecords.string(Map.of(
                    "payload", String.valueOf(record.getValue().get("payload")),
                    "originalId", id,
                    "error", String.valueOf(e.getMessage()))).withStreamKey(key + ".dead"),
                    RedisStreamCommands.XAddOptions.maxlen(DEAD_MAXLEN).approximateTrimming(true));
            if (dead == null) {
                throw e;
            }
            redis.opsForStream().acknowledge(key, GROUP, record.getId());
            failures.remove(id);
            return;
        }
        failures.remove(record.getId().getValue());
        redis.opsForStream().acknowledge(key, GROUP, record.getId());
        if (result.inserted()) {
            hub.publish(result.release().summary()); // SSE 비활성화(ADR-006) 동안 구독자가 없어 아무 일도 안 함
        }
    }

    private record Parsed(String tool, String releaseKey, String version, String title, String url,
                          Instant publishedAt, String body, String bodyFormat, boolean security, String source) {}

    private Parsed parse(Map<Object, Object> fields) {
        Object raw = fields.get("payload");
        if (raw == null) {
            throw new IllegalArgumentException("missing payload field");
        }
        JsonNode n = json.readTree(raw.toString());
        String tool = required(n, "tool");
        if (!Tools.exists(tool)) {
            throw new IllegalArgumentException("unknown tool: " + tool);
        }
        String source = required(n, "source");
        if (!source.equals("github") && !source.equals("rss")) {
            throw new IllegalArgumentException("unknown source: " + source);
        }
        JsonNode sec = n.get("security");
        if (sec == null || !sec.isBoolean()) {
            throw new IllegalArgumentException("security must be boolean");
        }
        return new Parsed(tool, required(n, "releaseKey"), optional(n, "version"), required(n, "title"),
                required(n, "url"), java.time.OffsetDateTime.parse(required(n, "publishedAt")).toInstant(),
                optional(n, "body"), optional(n, "bodyFormat"), sec.asBoolean(), source);
    }

    private static String required(JsonNode n, String field) {
        String v = optional(n, field);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        return v;
    }

    private static String optional(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
