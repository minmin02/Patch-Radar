package io.patchradar.collector.source;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 피드 원문 -> Release. 필터·버전·보안 규칙은 contracts/domain.md. */
public final class Parser {

    private static final Pattern TAG = Pattern.compile("^v?\\d+(\\.\\d+)+(\\+k3s\\d+)?$");
    private static final Pattern NUM_VERSION = Pattern.compile("\\d+(\\.\\d+)+");
    private static final Pattern JENKINS_TITLE = Pattern.compile("^Jenkins \\d+(\\.\\d+)+$");
    private static final Pattern TITLE_SECURITY = Pattern.compile("security|critical", Pattern.CASE_INSENSITIVE);
    private static final Pattern CVE = Pattern.compile("CVE-\\d{4}-\\d{4,}", Pattern.CASE_INSENSITIVE);
    // 제목 줄(#): Security 뒤 단어만 이어지다 ':' 또는 줄 끝. 그 외 줄: 단어만 이어지다 ':'로 끝나는 라벨.
    private static final Pattern SECURITY_LABEL = Pattern.compile(
            "^[ \\t]*(?:#+[ \\t]*security\\w*(?:[ \\t]+\\w+)*[ \\t]*(?::|$)"
                    + "|[-*]?[ \\t]*security\\w*(?:[ \\t]+\\w+)*[ \\t]*:)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern BLOCK_TAG = Pattern.compile("</?(?:p|li|br|h[1-6]|div|tr)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Logger log = LoggerFactory.getLogger(Parser.class);
    private static final int MAX_BODY = 200_000; // 스키마 body maxLength
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Parser() {
    }

    public static List<Release> github(String tool, String json) {
        List<Release> out = new ArrayList<>();
        for (JsonNode r : JSON.readTree(json)) {
            String tag = r.path("tag_name").asString("");
            try {
                if (r.path("draft").asBoolean(false) || r.path("prerelease").asBoolean(false)
                        || !TAG.matcher(tag).matches()) {
                    continue;
                }
                String published = r.path("published_at").asString("");
                String url = r.path("html_url").asString("");
                if (published.isEmpty()) {
                    continue;
                }
                if (!validUrl(url)) {
                    log.warn("skip {} {}: invalid url {}", tool, tag, url);
                    continue;
                }
                String name = clean(r.path("name").asString(""));
                String title = name.isBlank() ? tag : name;
                String body = text(r.path("body").asString(null));
                out.add(new Release(tool, tag, tag.startsWith("v") ? tag.substring(1) : tag, title,
                        url, Instant.parse(published), body,
                        body == null ? null : "markdown", security(title, body), "github"));
            } catch (RuntimeException e) {
                log.warn("skip {} {}: {}", tool, tag, e.toString());
            }
        }
        return out;
    }

    public static List<Release> rss(String tool, byte[] xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        List<Release> out = new ArrayList<>();
        // RSS(item)와 Atom(entry) 모두 처리
        for (String tagName : new String[] {"item", "entry"}) {
            NodeList nodes = doc.getElementsByTagName(tagName);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                String title = clean(child(e, "title"));
                try {
                    if (title == null || (tool.equals("jenkins") && !JENKINS_TITLE.matcher(title).matches())) {
                        continue;
                    }
                    String link = tagName.equals("entry") ? atomLink(e) : child(e, "link");
                    String date = first(child(e, "pubDate"), child(e, "published"), child(e, "updated"));
                    if (link == null || date == null) {
                        continue;
                    }
                    if (!validUrl(link)) {
                        log.warn("skip {} {}: invalid url {}", tool, title, link);
                        continue;
                    }
                    String body = text(first(child(e, "content:encoded"), child(e, "content"),
                            child(e, "description"), child(e, "summary")));
                    out.add(new Release(tool, link, highest(title), title, link, instant(date), body,
                            body == null ? null : "html", security(title, body), "rss"));
                } catch (RuntimeException ex) {
                    log.warn("skip {} {}: {}", tool, title, ex.toString());
                }
            }
        }
        return out;
    }

    /** 문자열 안의 숫자 버전 중 가장 높은 것(숫자 단위 비교). 없으면 null. */
    static String highest(String s) {
        List<String> all = new ArrayList<>();
        Matcher m = NUM_VERSION.matcher(s);
        while (m.find()) {
            all.add(m.group());
        }
        return all.stream().max(Parser::compare).orElse(null);
    }

    private static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Long.compare(i < x.length ? Long.parseLong(x[i]) : 0, i < y.length ? Long.parseLong(y[i]) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static boolean security(String title, String body) {
        if (TITLE_SECURITY.matcher(title).find()) {
            return true;
        }
        if (body == null) {
            return false;
        }
        return CVE.matcher(body).find() || SECURITY_LABEL.matcher(stripTags(body)).find();
    }

    private static String stripTags(String html) {
        return HTML_TAG.matcher(BLOCK_TAG.matcher(html).replaceAll("\n")).replaceAll(" ");
    }

    private static boolean validUrl(String u) {
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String clean(String s) {
        return s == null ? null : s.replace("\u0000", "");
    }

    private static String text(String s) {
        s = clean(s);
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() > MAX_BODY ? s.substring(0, MAX_BODY) : s;
    }

    private static String first(String... v) {
        for (String s : v) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private static Instant instant(String s) {
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(s, Instant::from);
        } catch (RuntimeException e) {
            return OffsetDateTime.parse(s).toInstant();
        }
    }

    /** 직계 자식 텍스트(트림). 비어 있으면 null. */
    private static String child(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getNodeName().equals(name)) {
                String t = c.getTextContent().trim();
                return t.isEmpty() ? null : t;
            }
        }
        return null;
    }

    private static String atomLink(Element e) {
        String href = null;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getNodeName().equals("link") && c.hasAttribute("href")) {
                String rel = c.getAttribute("rel");
                if (rel.isEmpty() || rel.equals("alternate")) {
                    return c.getAttribute("href");
                }
                if (href == null) {
                    href = c.getAttribute("href");
                }
            }
        }
        return href;
    }
}
