CREATE TABLE jfr_report (
    id                  TEXT PRIMARY KEY,
    original_filename   TEXT NOT NULL,
    recording_start     TIMESTAMPTZ,
    duration_millis     BIGINT NOT NULL,
    sample_count        BIGINT NOT NULL,
    thread_dump_count   INTEGER NOT NULL,
    dump_analysis_id    TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    summary             JSONB NOT NULL,
    hotspot             JSONB NOT NULL,
    threads             JSONB NOT NULL,
    dump_bundle         JSONB
);

CREATE INDEX jfr_report_created_at_idx ON jfr_report (created_at DESC);
CREATE INDEX jfr_report_expires_at_idx ON jfr_report (expires_at);
