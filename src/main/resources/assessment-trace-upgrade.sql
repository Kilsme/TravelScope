-- Apply assessment-upgrade.sql first. Additive and safe to execute repeatedly.
-- Older installed assessment tables predate first-token latency; the incremental path must supply it too.
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS ttft_ms DOUBLE PRECISION;
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS root_span_id VARCHAR(16);
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS collection_version VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS trace_attributes JSONB NOT NULL DEFAULT '{}';
ALTER TABLE assessment_runs ADD COLUMN IF NOT EXISTS trace_root_seen BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS trace_id VARCHAR(32);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS otel_span_id VARCHAR(16);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS otel_parent_span_id VARCHAR(16);
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS collection_version VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE assessment_spans ADD COLUMN IF NOT EXISTS trace_attributes JSONB NOT NULL DEFAULT '{}';
CREATE UNIQUE INDEX IF NOT EXISTS assessment_runs_native_span ON assessment_runs(trace_id,root_span_id) WHERE root_span_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS assessment_spans_native_span ON assessment_spans(trace_id,otel_span_id) WHERE otel_span_id IS NOT NULL;
