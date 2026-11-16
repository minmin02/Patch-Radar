-- 소스(tool id)별 조건부 요청 상태
create table fetch_state (
    tool          text primary key,
    etag          text,
    last_modified text,
    checked_at    timestamptz not null
);

-- (tool, releaseKey)별 마지막 발행 내용의 해시
create table published_release (
    tool         text        not null,
    release_key  text        not null,
    content_hash text        not null,
    published_at timestamptz not null default now(),
    primary key (tool, release_key)
);
