package io.patchradar.collector.source;

import java.time.Instant;

public record Release(String tool, String releaseKey, String version, String title, String url,
                      Instant publishedAt, String body, String bodyFormat, boolean security, String source) {
}
