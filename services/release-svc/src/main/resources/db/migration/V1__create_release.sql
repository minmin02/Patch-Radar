CREATE TABLE release (
    id           BIGSERIAL PRIMARY KEY,
    tool         TEXT        NOT NULL,
    release_key  TEXT        NOT NULL,
    version      TEXT,
    title        TEXT        NOT NULL,
    url          TEXT        NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    body         TEXT,
    body_format  TEXT,
    security     BOOLEAN     NOT NULL DEFAULT FALSE,
    source       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_release_tool_key UNIQUE (tool, release_key)
);

CREATE INDEX idx_release_published ON release (published_at DESC, id DESC);
CREATE INDEX idx_release_tool_published ON release (tool, published_at DESC, id DESC);
