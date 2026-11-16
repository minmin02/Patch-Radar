-- 검색용 텍스트: 제목 + 본문에서 HTML 태그 제거, 기본 엔티티 디코딩, 공백 정리.
-- 태그 제거를 먼저 해야 디코딩된 &lt; &gt; 가 태그로 오인되지 않는다. &amp; 는 마지막에 디코딩한다.
ALTER TABLE release ADD COLUMN search_text text GENERATED ALWAYS AS (
    btrim(regexp_replace(
        replace(replace(replace(replace(replace(replace(
            regexp_replace(title || ' ' || coalesce(body, ''), '<[^>]*>', ' ', 'g'),
            '&nbsp;', ' '), '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&#39;', ''''), '&amp;', '&'),
        '\s+', ' ', 'g'))
) STORED;
