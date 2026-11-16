package io.patchradar.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReleaseSvcTests {
    static final String KEY = "test.release.detected." + UUID.randomUUID();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("patchradar.stream.key", () -> KEY);
    }

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    final HttpClient http = HttpClient.newHttpClient();

    @org.junit.jupiter.api.AfterAll
    static void dropStream(@Autowired StringRedisTemplate r) {
        r.delete(KEY);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM release").update();
    }

    String event(String tool, String key, String title, String body, boolean security, String published) {
        return json.writeValueAsString(Map.ofEntries(
                Map.entry("eventId", UUID.randomUUID().toString()), Map.entry("occurredAt", "2026-11-16T01:20:00Z"),
                Map.entry("tool", tool), Map.entry("releaseKey", key), Map.entry("title", title),
                Map.entry("url", "https://example.com/" + key), Map.entry("publishedAt", published),
                Map.entry("body", body), Map.entry("bodyFormat", "markdown"), Map.entry("security", security),
                Map.entry("source", "github")));
    }

    volatile String lastId = "0-0";

    void send(String payload) {
        lastId = redis.opsForStream().add(KEY, Map.of("payload", payload)).getValue();
    }

    static boolean gte(String a, String b) {
        String[] x = a.split("-"), y = b.split("-");
        int c = Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0]));
        return c != 0 ? c > 0 : Long.parseLong(x[1]) >= Long.parseLong(y[1]);
    }

    /** 소비자가 마지막 XADD 메시지까지 전달받았고 pending이 없다. */
    boolean drained() {
        var g = redis.opsForStream().groups(KEY).stream().filter(i -> i.groupName().equals("release-svc")).findFirst();
        return g.isPresent() && gte(g.get().lastDeliveredId(), lastId) && pending() == 0;
    }

    long count() {
        return jdbc.sql("SELECT count(*) FROM release").query(Long.class).single();
    }

    void await(BooleanSupplier c) throws Exception {
        boolean ok = c.getAsBoolean();
        for (int i = 0; i < 100 && !ok; i++) {
            Thread.sleep(100);
            ok = c.getAsBoolean();
        }
        assertTrue(ok, "timeout");
    }

    long pending() {
        var p = redis.opsForStream().pending(KEY, "release-svc");
        return p == null ? 0 : p.getTotalPendingMessages();
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode getJson(String path) throws Exception {
        return json.readTree(get(path).body());
    }

    @Test
    void consumeIsIdempotentUpdatesAndAcksPoison() throws Exception {
        String e = event("argocd", "v3.6.1", "v3.6.1", "hello", false, "2026-11-10T12:00:00Z");
        send(e);
        await(() -> count() == 1);
        send(e);
        send("{not json");
        send(json.writeValueAsString(Map.of("tool", "argocd")));
        await(() -> drained());
        assertEquals(1, count());

        send(event("argocd", "v3.6.1", "v3.6.1 changed", "bye", true, "2026-11-10T12:00:00Z"));
        await(() -> jdbc.sql("SELECT count(*) FROM release WHERE title = 'v3.6.1 changed' AND security")
                .query(Long.class).single() == 1);
        assertEquals(1, count());
        await(this::drained);
    }

    @Test
    void poisonMessageMovesToDeadStreamAfterFiveFailures() throws Exception {
        // NUL 문자는 PostgreSQL text에 저장할 수 없어 upsert가 항상 실패한다
        send(event("argocd", "bad", "bad", "a\u0000b", false, "2026-11-10T12:00:00Z"));
        send(event("argocd", "good", "good", "ok", false, "2026-11-10T12:00:00Z"));
        String dead = KEY + ".dead";
        try {
            await(() -> Boolean.TRUE.equals(redis.hasKey(dead)));
            await(() -> count() == 1 && drained());
            assertEquals(1, redis.opsForStream().size(dead));
            var rec = redis.opsForStream().range(dead, org.springframework.data.domain.Range.unbounded()).get(0);
            assertTrue(rec.getValue().get("payload").toString().contains("\"releaseKey\":\"bad\""));
            assertTrue(!rec.getValue().get("error").toString().isEmpty());
            assertTrue(rec.getValue().get("originalId").toString().matches("\\d+-\\d+"));
            assertEquals("good", jdbc.sql("SELECT release_key FROM release").query(String.class).single());
        } finally {
            redis.delete(dead);
        }
    }

    @Test
    void api() throws Exception {
        send(event("argocd", "v1", "Argo One", "Fixes CVE-1", true, "2026-11-01T00:00:00Z"));
        send(event("argocd", "v2", "Argo Two", "plain", false, "2026-11-03T00:00:00Z"));
        send(event("harbor", "v1", "Harbor 100%", "x_y", false, "2026-11-02T00:00:00Z"));
        await(() -> count() == 3);

        JsonNode all = getJson("/api/releases");
        assertEquals(3, all.get("total").asInt());
        assertEquals(20, all.get("size").asInt());
        assertEquals("Argo Two", all.get("items").get(0).get("title").asString());
        assertEquals("Harbor 100%", all.get("items").get(1).get("title").asString());

        assertEquals(2, getJson("/api/releases?tool=argocd").get("total").asInt());
        assertEquals(3, getJson("/api/releases?tool=argocd&tool=harbor").get("total").asInt());
        assertEquals(1, getJson("/api/releases?security=true").get("total").asInt());
        assertEquals(1, getJson("/api/releases?q=cve-1").get("total").asInt());
        assertEquals(1, getJson("/api/releases?q=100%25").get("total").asInt());
        assertEquals(0, getJson("/api/releases?q=a%25").get("total").asInt());
        assertEquals(2, getJson("/api/releases?from=2026-11-02T00:00:00Z").get("total").asInt());
        assertEquals(1, getJson("/api/releases?from=2026-11-02T00:00:00Z&to=2026-11-03T00:00:00Z").get("total").asInt());
        JsonNode p = getJson("/api/releases?size=1&page=2");
        assertEquals(1, p.get("items").size());
        assertEquals("Argo One", p.get("items").get(0).get("title").asString());

        long id = all.get("items").get(0).get("id").asLong();
        assertEquals("plain", getJson("/api/releases/" + id).get("body").asString());
        assertEquals("markdown", getJson("/api/releases/" + id).get("bodyFormat").asString());

        JsonNode tools = getJson("/api/tools");
        assertEquals(9, tools.size());
        for (JsonNode t : tools) {
            if (t.get("id").asString().equals("argocd")) {
                assertEquals("Argo Two", t.get("latest").get("title").asString());
            }
            if (t.get("id").asString().equals("k3s")) {
                assertTrue(t.get("latest").isNull());
            }
        }
    }

    @Test
    void searchIgnoresHtmlMarkupAndTrimsQuery() throws Exception {
        send(event("argocd", "h1", "HTML", "<p class=\"x\"><strong>Fix</strong> sync bug</p>", false,
                "2026-11-01T00:00:00Z"));
        send(event("argocd", "h2", "Ent", "A &amp; B &lt;ok&gt;", false, "2026-11-02T00:00:00Z"));
        await(() -> count() == 2);

        for (String q : new String[] {"strong", "class", "p"}) {
            assertEquals(0, getJson("/api/releases?q=" + q).get("total").asInt(), q);
        }
        assertEquals(1, getJson("/api/releases?q=sync").get("total").asInt());
        assertEquals(1, getJson("/api/releases?q=%20%20sync%20%20").get("total").asInt());
        assertEquals(2, getJson("/api/releases?q=%20%20%20").get("total").asInt());
        assertEquals(1, getJson("/api/releases?q=A%20%26%20B").get("total").asInt());
        assertEquals(1, getJson("/api/releases?q=%3Cok%3E").get("total").asInt());
    }

    @Test
    void errors() throws Exception {
        for (String bad : new String[] {"tool=nope", "size=101", "size=0", "page=-1", "from=yesterday", "to=x",
                "security=maybe", "size=abc"}) {
            var r = get("/api/releases?" + bad);
            assertEquals(400, r.statusCode(), bad);
            assertEquals("BAD_REQUEST", json.readTree(r.body()).get("code").asString());
            assertTrue(json.readTree(r.body()).has("message"));
        }
        var r = get("/api/releases/999999");
        assertEquals(404, r.statusCode());
        assertEquals("NOT_FOUND", json.readTree(r.body()).get("code").asString());
    }

    @Test
    void sseOnlyNewInserts() throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/releases/stream"))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        var reader = new BufferedReader(new InputStreamReader(resp.body()));
        var lines = CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                String l;
                while ((l = reader.readLine()) != null) {
                    sb.append(l).append('\n');
                    if (l.startsWith("data:")) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            return sb.toString();
        });
        send(event("harbor", "s1", "Sse One", "a", false, "2026-11-01T00:00:00Z"));
        String got = lines.get(10, TimeUnit.SECONDS);
        assertTrue(got.contains("event:release"), got);
        assertTrue(got.contains("\"title\":\"Sse One\""), got);
        reader.close();
    }
}
