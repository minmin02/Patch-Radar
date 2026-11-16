package io.patchradar.release.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReleaseStore {
    public record Upserted(Release release, boolean inserted) {}

    public record Filter(List<String> tools, Instant from, Instant to, String q, Boolean security) {}

    private static final String COLS = "id, tool, version, title, url, published_at, security, body, body_format";

    private final JdbcClient jdbc;

    public ReleaseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** (tool, release_key) 기준 upsert. 단일 문장이라 반환 시점에 이미 커밋됐다. */
    public Upserted upsert(String tool, String releaseKey, String version, String title, String url,
                           Instant publishedAt, String body, String bodyFormat, boolean security, String source) {
        return jdbc.sql("""
                INSERT INTO release (tool, release_key, version, title, url, published_at, body, body_format, security, source)
                VALUES (:tool, :key, :version, :title, :url, :pub, :body, :bf, :sec, :src)
                ON CONFLICT (tool, release_key) DO UPDATE SET
                    version = EXCLUDED.version, title = EXCLUDED.title, url = EXCLUDED.url,
                    published_at = EXCLUDED.published_at, body = EXCLUDED.body, body_format = EXCLUDED.body_format,
                    security = EXCLUDED.security, source = EXCLUDED.source, updated_at = now()
                RETURNING %s, (xmax = 0) AS inserted
                """.formatted(COLS))
                .param("tool", tool).param("key", releaseKey).param("version", version).param("title", title)
                .param("url", url).param("pub", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .param("body", body).param("bf", bodyFormat).param("sec", security).param("src", source)
                .query((rs, i) -> new Upserted(map(rs), rs.getBoolean("inserted")))
                .single();
    }

    public Optional<Release> find(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM release WHERE id = :id").param("id", id)
                .query((rs, i) -> map(rs)).optional();
    }

    /** 도구별 가장 최근 릴리스. */
    public List<Release> latestPerTool() {
        return jdbc.sql("SELECT DISTINCT ON (tool) " + COLS + " FROM release ORDER BY tool, published_at DESC, id DESC")
                .query((rs, i) -> map(rs)).list();
    }

    public List<Release> search(Filter f, int page, int size) {
        var where = new StringBuilder();
        var params = new ArrayList<Object>();
        where(f, where, params);
        params.add(size);
        params.add((long) page * size);
        return jdbc.sql("SELECT " + COLS + " FROM release" + where
                        + " ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params).query((rs, i) -> map(rs)).list();
    }

    public long count(Filter f) {
        var where = new StringBuilder();
        var params = new ArrayList<Object>();
        where(f, where, params);
        return jdbc.sql("SELECT count(*) FROM release" + where).params(params).query(Long.class).single();
    }

    private static void where(Filter f, StringBuilder sql, List<Object> params) {
        var conds = new ArrayList<String>();
        if (f.tools() != null && !f.tools().isEmpty()) {
            conds.add("tool IN (" + String.join(",", f.tools().stream().map(t -> "?").toList()) + ")");
            params.addAll(f.tools());
        }
        if (f.from() != null) {
            conds.add("published_at >= ?");
            params.add(OffsetDateTime.ofInstant(f.from(), ZoneOffset.UTC));
        }
        if (f.to() != null) {
            conds.add("published_at < ?");
            params.add(OffsetDateTime.ofInstant(f.to(), ZoneOffset.UTC));
        }
        if (f.q() != null && !f.q().isEmpty()) {
            String like = "%" + f.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            conds.add("search_text ILIKE ?");
            params.add(like);
        }
        if (Boolean.TRUE.equals(f.security())) {
            conds.add("security");
        }
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
    }

    private static Release map(ResultSet rs) throws SQLException {
        return new Release(rs.getLong("id"), rs.getString("tool"), rs.getString("version"), rs.getString("title"),
                rs.getString("url"), rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("security"), rs.getString("body"), rs.getString("body_format"));
    }
}
