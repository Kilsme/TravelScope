-- Additive, idempotent migration. Run before deploying the reconnect backend.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS turn_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS messages_turn_role ON messages(user_id,turn_id,role)
 WHERE turn_id IS NOT NULL AND role IN ('user','assistant');
CREATE TABLE IF NOT EXISTS chat_turns (
 user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 turn_id UUID NOT NULL, conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 request_hash TEXT NOT NULL, input_text TEXT NOT NULL, source_turn_id UUID, form_consumed_by UUID,
 image_key TEXT, image_type TEXT, status VARCHAR(20) NOT NULL,
 user_message_id BIGINT, assistant_message_id BIGINT,
 charged_at TIMESTAMPTZ, charged_points INTEGER,
 last_seq BIGINT NOT NULL DEFAULT 0, snapshot_seq BIGINT NOT NULL DEFAULT 0,
 snapshot_json JSONB NOT NULL DEFAULT '{"content":"","activities":[],"itineraryDays":[]}',
 outcome VARCHAR(40), error_code TEXT, error_message TEXT,
 events_expired BOOLEAN NOT NULL DEFAULT false, image_cleaned BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ,
 PRIMARY KEY(user_id,turn_id),
 CHECK (status IN ('PREPARING','QUEUED','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS chat_turns_active_conversation ON chat_turns(conversation_id)
 WHERE status IN ('PREPARING','QUEUED','RUNNING','CANCELLING');
CREATE INDEX IF NOT EXISTS chat_turns_dispatch ON chat_turns(status,created_at);
CREATE INDEX IF NOT EXISTS chat_turns_history ON chat_turns(user_id,conversation_id,created_at);
CREATE TABLE IF NOT EXISTS chat_turn_events (
 user_id BIGINT NOT NULL, turn_id UUID NOT NULL, seq BIGINT NOT NULL,
 event_type VARCHAR(50) NOT NULL, payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,turn_id,seq),
 FOREIGN KEY(user_id,turn_id) REFERENCES chat_turns(user_id,turn_id) ON DELETE CASCADE
);

-- Additive upgrade; old hashes and messages remain untouched.
ALTER TABLE chat_turns ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN' CHECK (locale IN ('zh-CN','en'));
ALTER TABLE chat_turns ADD COLUMN IF NOT EXISTS response_locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN' CHECK (response_locale IN ('zh-CN','en'));
ALTER TABLE chat_turns ADD COLUMN IF NOT EXISTS request_hash_version SMALLINT NOT NULL DEFAULT 1 CHECK (request_hash_version IN (1,2));
