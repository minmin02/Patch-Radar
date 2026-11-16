package io.patchradar.collector.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ParserTest {

    private static String feed(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/feeds", name));
    }

    @Test
    void k3sExcludesPrereleaseKeepsStable() throws Exception {
        List<Release> r = Parser.github("k3s", feed("github-k3s.json"));
        assertThat(r).extracting(Release::releaseKey).containsExactly("v1.37.2+k3s1", "v1.36.6+k3s1");
        assertThat(r.get(0).version()).isEqualTo("1.37.2+k3s1");
        assertThat(r.get(0).bodyFormat()).isEqualTo("markdown");
        assertThat(r.get(0).source()).isEqualTo("github");
    }

    @Test
    void sonarqubeFourPartVersion() throws Exception {
        List<Release> r = Parser.github("sonarqube", feed("github-sonarqube.json"));
        assertThat(r).extracting(Release::version).contains("26.11.0.134175");
    }

    @Test
    void githubTagAndFlagFilters() {
        String json = """
                [{"tag_name":"stable","prerelease":false,"draft":false,"published_at":"2026-01-01T00:00:00Z"},
                 {"tag_name":"gitops-engine/v3.6.1","prerelease":false,"draft":false,"published_at":"2026-01-01T00:00:00Z"},
                 {"tag_name":"v1.37.2-rc1+k3s1","prerelease":false,"draft":false,"published_at":"2026-01-01T00:00:00Z"},
                 {"tag_name":"v1.0.0","prerelease":false,"draft":true,"published_at":"2026-01-01T00:00:00Z"},
                 {"tag_name":"v1.0.1","prerelease":true,"draft":false,"published_at":"2026-01-01T00:00:00Z"},
                 {"tag_name":"v1.37.2+k3s1","name":"","body":"fix CVE-2026-12345","prerelease":false,"draft":false,
                  "published_at":"2026-01-01T00:00:00Z","html_url":"https://example.com/x"}]""";
        List<Release> r = Parser.github("k3s", json);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).title()).isEqualTo("v1.37.2+k3s1");
        assertThat(r.get(0).security()).isTrue();
    }

    @Test
    void gitlabAtomHighestVersionAndSecurity() throws Exception {
        List<Release> r = Parser.rss("gitlab", Files.readAllBytes(Path.of("src/test/resources/feeds/gitlab.xml")));
        assertThat(r).hasSize(5);
        Release first = r.get(0);
        assertThat(first.title()).isEqualTo("GitLab Patch Release: 19.5.5, 19.4.7");
        assertThat(first.version()).isEqualTo("19.5.5");
        assertThat(first.releaseKey()).isEqualTo(first.url()).startsWith("https://");
        assertThat(first.bodyFormat()).isEqualTo("html");
        assertThat(first.source()).isEqualTo("rss");
        assertThat(r.get(1).security()).isTrue(); // "Critical Patch Release"
    }

    @Test
    void jenkinsRssOnlyVersionTitles() throws Exception {
        List<Release> r = Parser.rss("jenkins", Files.readAllBytes(Path.of("src/test/resources/feeds/jenkins.xml")));
        assertThat(r).extracting(Release::title).containsExactly("Jenkins 2.580.2", "Jenkins 2.580.1", "Jenkins 2.568.3");
        assertThat(r.get(0).version()).isEqualTo("2.580.2");
        assertThat(r.get(0).security()).isTrue();
        assertThat(r.get(0).bodyFormat()).isEqualTo("html");
        assertThat(r.get(0).publishedAt()).hasToString("2026-10-28T00:00:00Z");
    }

    @Test
    void highestComparesNumerically() {
        assertThat(Parser.highest("Release 19.5.5, 19.10.1, 19.9.9")).isEqualTo("19.10.1");
        assertThat(Parser.highest("no version")).isNull();
    }

    private static String gh(String tag, String name, String body, String url, String date) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"" + name + "\",\"body\":\"" + body
                + "\",\"prerelease\":false,\"draft\":false,\"published_at\":\"" + date
                + "\",\"html_url\":\"" + url + "\"}";
    }

    private static boolean sec(String title, String body) {
        return Parser.github("t", "[" + gh("v1.0.0", title, body, "https://e.com/x", "2026-01-01T00:00:00Z") + "]")
                .get(0).security();
    }

    @Test
    void securityRules() {
        assertThat(sec("v1", "does not include any security fixes")).isFalse();
        assertThat(sec("v1", "## Security")).isTrue();
        assertThat(sec("v1", "Security:\\nImportant security fixes.")).isTrue();
        assertThat(sec("v1", "Fixes CVE-2026-12345")).isTrue();
        assertThat(sec("GitLab Critical Patch Release", "x")).isTrue();
        assertThat(sec("v1", "- Bump spring-security to 6.5")).isFalse();
        assertThat(sec("v1", "### Security Fixes")).isTrue();
        assertThat(sec("v1", "<p>Updated the <a href=x>security</a> scanner docs.</p>")).isFalse();
        assertThat(sec("v1", "<li>Improve <code>security</code> realm UI</li>")).isFalse();
        assertThat(sec("v1", "Security team reviewed this release")).isFalse();
        assertThat(sec("v1", "<li>Security:\\nImportant security fixes.</li>")).isTrue();
        assertThat(sec("v1", "- Security fixes:")).isTrue();
    }

    @Test
    void badItemsAreSkippedIndividually() {
        String json = "[" + gh("v1.0.0", "a", "b", "https://e.com/a", "garbage") + ","
                + gh("v1.0.1", "a", "b", "ftp://e.com/b", "2026-01-01T00:00:00Z") + ","
                + gh("v1.0.2", "a\\u0000b", "c", "https://e.com/c", "2026-01-01T00:00:00Z") + "]";
        List<Release> r = Parser.github("t", json);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).releaseKey()).isEqualTo("v1.0.2");
        assertThat(r.get(0).title()).isEqualTo("ab");
    }

    @Test
    void rssBadDateSkipsOnlyThatItem() throws Exception {
        String xml = """
                <rss><channel>
                <item><title>A 1.0</title><link>https://e.com/a</link><pubDate>nope</pubDate></item>
                <item><title>B 1.1</title><link>https://e.com/b</link><pubDate>Mon, 05 Jan 2026 10:00:00 GMT</pubDate></item>
                </channel></rss>""";
        List<Release> r = Parser.rss("gitlab", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).title()).isEqualTo("B 1.1");
    }

    @Test
    void rssAndAtomDecodeUtf8Bytes() throws Exception {
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><item><title>A 1.0 eMudhra\u2019s caf\u00e9 \ud55c\uae00</title>"
                + "<link>https://e.com/a</link><pubDate>Mon, 05 Jan 2026 10:00:00 GMT</pubDate></item></channel></rss>";
        String atom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><feed><entry><title>B 1.1 eMudhra\u2019s caf\u00e9 \ud55c\uae00</title>"
                + "<link href=\"https://e.com/b\"/><updated>2026-01-05T10:00:00Z</updated></entry></feed>";
        for (String xml : new String[] {rss, atom}) {
            List<Release> r = Parser.rss("gitlab", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(r.get(0).title()).endsWith("eMudhra\u2019s caf\u00e9 \ud55c\uae00");
        }
    }
}
