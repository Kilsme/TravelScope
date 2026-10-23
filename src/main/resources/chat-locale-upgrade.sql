-- Additive upgrade; old hashes and messages remain untouched.
ALTER TABLE chat_turns ADD COLUMN IF NOT EXISTS locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN' CHECK (locale IN ('zh-CN','en'));
ALTER TABLE chat_turns ADD COLUMN IF NOT EXISTS response_locale VARCHAR(5) NOT NULL DEFAULT 'zh-CN' CHECK (response_locale IN ('zh-CN','en'));
ALTER TABLE chat_turns ADD COLUMN IF NOT EXISTS request_hash_version SMALLINT NOT NULL DEFAULT 1 CHECK (request_hash_version IN (1,2));
