package io.patchradar.collector.source;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.patchradar.collector.config.Sources;
import io.patchradar.collector.publish.Publisher;

@Component
public class Collector {

    private static final Logger log = LoggerFactory.getLogger(Collector.class);

    private final JdbcClient db;
    private final Publisher publisher;
    private final String githubToken;
    private final RestClient http = httpClient();

    private static RestClient httpClient() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, "patch-radar-collector")
                .build();
    }

    public Collector(JdbcClient db, Publisher publisher, @Value("${patchradar.github.token:}") String githubToken) {
        this.db = db;
        this.publisher = publisher;
        this.githubToken = githubToken;
    }

    @Scheduled(fixedDelayString = "${patchradar.poll.interval:PT10M}",
            initialDelayString = "${patchradar.poll.initial-delay:PT5S}")
    public void pollAll() {
        for (Sources s : Sources.ALL) {
            try {
                poll(s);
            } catch (Exception e) {
                log.warn("poll failed: {}", s.tool(), e); // 실패한 소스는 상태 갱신 없이 다음 주기에 재시도
            }
        }
    }

    void poll(Sources s) throws Exception {
        var state = db.sql("select etag, last_modified from fetch_state where tool = ?").param(s.tool())
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional().orElse(new String[2]);
        boolean gh = s.type().equals("github");
        var res = http.get()
                .uri(gh ? "https://api.github.com/repos/" + s.location() + "/releases?per_page=30" : s.location())
                .headers(h -> {
                    if (gh) {
                        h.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
                        if (!githubToken.isBlank()) {
                            h.setBearerAuth(githubToken);
                        }
                    }
                    if (state[0] != null) {
                        h.set(HttpHeaders.IF_NONE_MATCH, state[0]);
                    }
                    if (state[1] != null) {
                        h.set(HttpHeaders.IF_MODIFIED_SINCE, state[1]);
                    }
                })
                .retrieve().toEntity(byte[].class);
        if (res.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
            return;
        }
        List<Release> releases = gh ? Parser.github(s.tool(), new String(res.getBody(), StandardCharsets.UTF_8)) : Parser.rss(s.tool(), res.getBody());
        int n = publishChanged(releases);
        db.sql("""
                insert into fetch_state (tool, etag, last_modified, checked_at) values (?, ?, ?, now())
                on conflict (tool) do update set etag = excluded.etag,
                  last_modified = excluded.last_modified, checked_at = excluded.checked_at""")
                .params(s.tool(), res.getHeaders().getETag(), res.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED))
                .update();
        log.info("polled {}: {} releases, {} published", s.tool(), releases.size(), n);
    }

    /** 처음 보거나 내용이 바뀐 릴리스만 발행하고 해시를 저장. 발행 수를 반환. */
    public int publishChanged(List<Release> releases) {
        int n = 0;
        for (Release r : releases) {
            String hash = hash(r);
            String old = db.sql("select content_hash from published_release where tool = ? and release_key = ?")
                    .params(r.tool(), r.releaseKey()).query(String.class).optional().orElse(null);
            if (hash.equals(old)) {
                continue;
            }
            publisher.publish(r);
            db.sql("""
                    insert into published_release (tool, release_key, content_hash) values (?, ?, ?)
                    on conflict (tool, release_key) do update set content_hash = excluded.content_hash,
                      published_at = now()""").params(r.tool(), r.releaseKey(), hash).update();
            n++;
        }
        return n;
    }

    private static String hash(Release r) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.toString().getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
