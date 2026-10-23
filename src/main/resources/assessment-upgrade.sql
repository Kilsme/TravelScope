-- Idempotent assessment upgrade; run manually before enabling collection.
CREATE TABLE IF NOT EXISTS assessment_runs (
 id UUID PRIMARY KEY, turn_id UUID NOT NULL, trace_id VARCHAR(64), session_id VARCHAR(255),
 sample_id UUID, source VARCHAR(20) NOT NULL DEFAULT 'ONLINE', task_type VARCHAR(40),
 status VARCHAR(40) NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ,
 duration_ms DOUBLE PRECISION, queue_ms DOUBLE PRECISION, data_complete BOOLEAN NOT NULL DEFAULT true,
 artifact_available BOOLEAN NOT NULL DEFAULT false, version_json TEXT NOT NULL DEFAULT '{}'
);
-- Nullable for legacy runs: never guess their final artifact from unordered history.
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS planning_artifact_id UUID;
CREATE TABLE IF NOT EXISTS assessment_spans (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES assessment_runs(id) ON DELETE CASCADE,
 parent_id UUID, phase VARCHAR(30) NOT NULL DEFAULT 'PLANNING', kind VARCHAR(30) NOT NULL, name TEXT NOT NULL, call_id TEXT,
 status VARCHAR(30) NOT NULL, started_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
 duration_ms DOUBLE PRECISION, model TEXT, input_tokens BIGINT, output_tokens BIGINT,
 cached_tokens BIGINT, reasoning_tokens BIGINT, usage_kind VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
 request_id TEXT, error_type TEXT, attempt_group UUID, attempt_index INTEGER,
 cost NUMERIC(20,10), price_version TEXT, cost_complete BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS assessment_runs_started ON assessment_runs(started_at DESC);
CREATE INDEX IF NOT EXISTS assessment_runs_sample ON assessment_runs(sample_id);
CREATE INDEX IF NOT EXISTS assessment_spans_run ON assessment_spans(run_id, started_at);
CREATE TABLE IF NOT EXISTS assessment_payloads (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES assessment_runs(id) ON DELETE CASCADE,
 span_id UUID REFERENCES assessment_spans(id) ON DELETE CASCADE, kind VARCHAR(30) NOT NULL,
 name TEXT, body TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS assessment_payloads_span ON assessment_payloads(span_id);
CREATE TABLE IF NOT EXISTS assessment_batches (
 id UUID PRIMARY KEY, request_key VARCHAR(128) NOT NULL UNIQUE, status VARCHAR(30) NOT NULL,
 tool_mode VARCHAR(20) NOT NULL, repetitions INTEGER NOT NULL CHECK(repetitions BETWEEN 1 AND 10),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ,
 config_json TEXT NOT NULL, cancel_requested BOOLEAN NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX IF NOT EXISTS assessment_single_active_batch ON assessment_batches ((true)) WHERE status IN ('QUEUED','RUNNING','CANCELLING');
CREATE TABLE IF NOT EXISTS assessment_samples (
 id UUID PRIMARY KEY, batch_id UUID NOT NULL REFERENCES assessment_batches(id) ON DELETE CASCADE,
 case_id TEXT NOT NULL, repetition INTEGER NOT NULL, case_json TEXT NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'QUEUED', rule_json TEXT, score_json TEXT,
 score_status VARCHAR(30) NOT NULL DEFAULT 'PENDING', session_id TEXT NOT NULL,
 UNIQUE(batch_id,case_id,repetition)
);
CREATE TABLE IF NOT EXISTS assessment_evaluations (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES assessment_runs(id) ON DELETE CASCADE,
 sample_id UUID, status VARCHAR(30) NOT NULL, result_json TEXT, scorer_version TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS phase VARCHAR(30) NOT NULL DEFAULT 'PLANNING';
-- TTFT (time to first token), for MODEL spans; null when the stream produced no element or predates the metric.
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS ttft_ms DOUBLE PRECISION;

-- Apply assessment-upgrade.sql first. Additive and safe to execute repeatedly.
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS root_span_id VARCHAR(16);
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS collection_version VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS trace_attributes JSONB NOT NULL DEFAULT '{}';
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS trace_id VARCHAR(32);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS otel_span_id VARCHAR(16);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS otel_parent_span_id VARCHAR(16);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS collection_version VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS trace_attributes JSONB NOT NULL DEFAULT '{}';
CREATE UNIQUE INDEX IF NOT EXISTS assessment_runs_native_span ON assessment_runs(trace_id,root_span_id) WHERE root_span_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS assessment_spans_native_span ON assessment_spans(trace_id,otel_span_id) WHERE otel_span_id IS NOT NULL;

ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS trace_root_seen BOOLEAN NOT NULL DEFAULT true;
