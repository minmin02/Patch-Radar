package io.patchradar.release.store;

import java.time.Instant;

/** 상세 표현. 목록/SSE는 {@link Summary}. */
public record Release(long id, String tool, String version, String title, String url, Instant publishedAt,
                      boolean security, String body, String bodyFormat) {

    public record Summary(long id, String tool, String version, String title, String url, Instant publishedAt,
                          boolean security) {}

    public Summary summary() {
        return new Summary(id, tool, version, title, url, publishedAt, security);
    }
}
