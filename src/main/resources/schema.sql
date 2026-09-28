-- ============================================================================
-- TravelScope - 智能旅游助手 数据库初始化脚本
-- 数据库: PostgreSQL (同时作为业务数据库和向量数据库)
-- 说明: 执行前请先创建数据库 travelscope
--   CREATE DATABASE travelscope;
-- ============================================================================

-- ======================== 扩展 ========================

-- pgvector 向量扩展（用于文档块向量检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================================
-- 1. 用户表 (users)
-- ============================================================================
CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)   NOT NULL UNIQUE,                          -- 用户名
    password        VARCHAR(255)  NOT NULL,                                 -- 密码（BCrypt加密）
    nickname        VARCHAR(64),                                            -- 昵称
    email           VARCHAR(128),                                           -- 邮箱
    phone           VARCHAR(20),                                            -- 手机号
    avatar_url      VARCHAR(512),                                           -- 头像URL（存储在MinIO）
    role            VARCHAR(20)   NOT NULL DEFAULT 'user',                  -- 角色: user / admin
    status          SMALLINT      NOT NULL DEFAULT 1,                       -- 状态: 0=禁用 1=启用
    last_login_at   TIMESTAMP,                                              -- 最后登录时间
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,      -- 创建时间
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP       -- 更新时间
);

-- 用户名索引
CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);

COMMENT ON TABLE  users IS '用户表';
COMMENT ON COLUMN users.id IS '主键ID';
COMMENT ON COLUMN users.username IS '用户名（唯一）';
COMMENT ON COLUMN users.password IS '密码（BCrypt加密）';
COMMENT ON COLUMN users.nickname IS '昵称';
COMMENT ON COLUMN users.email IS '邮箱';
COMMENT ON COLUMN users.phone IS '手机号';
COMMENT ON COLUMN users.avatar_url IS '头像URL（存储在MinIO）';
COMMENT ON COLUMN users.role IS '角色: user-普通用户 admin-管理员';
COMMENT ON COLUMN users.status IS '状态: 0-禁用 1-启用';
COMMENT ON COLUMN users.last_login_at IS '最后登录时间';
COMMENT ON COLUMN users.created_at IS '创建时间';
COMMENT ON COLUMN users.updated_at IS '更新时间';

-- ============================================================================
-- 2. 会话表 (conversations)
-- ============================================================================
CREATE TABLE IF NOT EXISTS conversations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL,                                 -- 用户ID
    title           VARCHAR(256)  NOT NULL DEFAULT '新会话',                  -- 会话标题
    agent_type      VARCHAR(64)   NOT NULL DEFAULT 'travel_assistant',      -- Agent类型
    status          VARCHAR(20)   NOT NULL DEFAULT 'active',                -- 状态: active / archived
    summary         TEXT,                                                   -- 会话记忆摘要（每满10轮把窗口外历史增量折叠）
    summary_covered_messages INTEGER NOT NULL DEFAULT 0,                    -- 记忆摘要已覆盖的消息条数（增量折叠进度）
    metadata        JSONB,                                                  -- 扩展元数据
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,        -- 创建时间
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP       -- 更新时间
);

-- 外键约束
ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_user_id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- 索引
CREATE INDEX IF NOT EXISTS idx_conversations_user_id ON conversations (user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_status ON conversations (status);
CREATE INDEX IF NOT EXISTS idx_conversations_created_at ON conversations (created_at DESC);

COMMENT ON TABLE  conversations IS '会话表';
COMMENT ON COLUMN conversations.id IS '主键ID';
COMMENT ON COLUMN conversations.user_id IS '用户ID（关联users表）';
COMMENT ON COLUMN conversations.title IS '会话标题';
COMMENT ON COLUMN conversations.agent_type IS 'Agent类型: travel_assistant-旅游助手';
COMMENT ON COLUMN conversations.status IS '状态: active-活跃 archived-已归档';
COMMENT ON COLUMN conversations.summary IS '会话记忆摘要（每满10轮把窗口外历史增量折叠，注入模型作长期上下文）';
COMMENT ON COLUMN conversations.summary_covered_messages IS '记忆摘要已覆盖的消息条数（增量折叠进度）';
COMMENT ON COLUMN conversations.metadata IS '扩展元数据（JSON格式）';
COMMENT ON COLUMN conversations.created_at IS '创建时间';
COMMENT ON COLUMN conversations.updated_at IS '更新时间';

-- 记忆摘要列升级（2026-09-29 失忆修复）：存量库补列，幂等可重复执行
-- （CREATE TABLE IF NOT EXISTS 不会给已存在的表加列，存量库需执行以下语句）
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS summary TEXT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS summary_covered_messages INTEGER NOT NULL DEFAULT 0;

-- ============================================================================
-- 3. 消息表 (messages)
-- ============================================================================
CREATE TABLE IF NOT EXISTS messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT        NOT NULL,                                 -- 会话ID
    user_id         BIGINT        NOT NULL,                                 -- 用户ID
    role            VARCHAR(20)   NOT NULL,                                 -- 角色: user / assistant / tool
    content         TEXT          NOT NULL,                                 -- 消息内容
    content_blocks  JSONB,                                                  -- 多模态内容块（文本/图片/文件等）
    tool_calls      JSONB,                                                  -- 工具调用信息
    tool_call_id    VARCHAR(128),                                           -- 工具调用ID（当role=tool时）
    token_count     INTEGER       DEFAULT 0,                                -- Token数量
    model_name      VARCHAR(128),                                           -- 使用的模型名称
    metadata        JSONB,                                                  -- 扩展元数据
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP         -- 创建时间
);

-- 外键约束
ALTER TABLE messages
    ADD CONSTRAINT fk_messages_conversation_id
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE;

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_user_id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

-- 索引
CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON messages (conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_user_id ON messages (user_id);
CREATE INDEX IF NOT EXISTS idx_messages_role ON messages (role);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages (created_at);

COMMENT ON TABLE  messages IS '消息表';
COMMENT ON COLUMN messages.id IS '主键ID';
COMMENT ON COLUMN messages.conversation_id IS '会话ID（关联conversations表）';
COMMENT ON COLUMN messages.user_id IS '用户ID（关联users表）';
COMMENT ON COLUMN messages.role IS '角色: user-用户消息 assistant-助手消息 tool-工具消息';
COMMENT ON COLUMN messages.content IS '消息文本内容';
COMMENT ON COLUMN messages.content_blocks IS '多模态内容块（JSON: 文本/图片/文件等）';
COMMENT ON COLUMN messages.tool_calls IS '工具调用信息（JSON）';
COMMENT ON COLUMN messages.tool_call_id IS '工具调用ID（当role=tool时关联工具调用）';
COMMENT ON COLUMN messages.token_count IS 'Token数量';
COMMENT ON COLUMN messages.model_name IS '使用的模型名称';
COMMENT ON COLUMN messages.metadata IS '扩展元数据（JSON格式）';
COMMENT ON COLUMN messages.created_at IS '创建时间';

-- ============================================================================
-- 4. 文档表 (documents)
-- ============================================================================
CREATE TABLE IF NOT EXISTS documents (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(512)  NOT NULL,                                 -- 文档标题
    file_name       VARCHAR(512),                                           -- 原始文件名
    file_type       VARCHAR(50),                                            -- 文件类型: pdf / docx / txt / md / html
    file_size       BIGINT        DEFAULT 0,                                 -- 文件大小（字节）
    file_path       VARCHAR(1024),                                           -- MinIO存储路径
    file_url        VARCHAR(1024),                                           -- MinIO访问URL
    content         TEXT,                                                   -- 文档原始文本内容
    chunk_count     INTEGER       DEFAULT 0,                                 -- 文档块数量
    status          VARCHAR(20)   NOT NULL DEFAULT 'pending',               -- 状态: pending / processing / completed / failed
    error_message   TEXT,                                                   -- 错误信息
    metadata        JSONB,                                                  -- 扩展元数据
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,       -- 创建时间
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP       -- 更新时间
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents (status);
CREATE INDEX IF NOT EXISTS idx_documents_file_type ON documents (file_type);
CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents (created_at DESC);

COMMENT ON TABLE  documents IS '文档表';
COMMENT ON COLUMN documents.id IS '主键ID';
COMMENT ON COLUMN documents.title IS '文档标题';
COMMENT ON COLUMN documents.file_name IS '原始文件名';
COMMENT ON COLUMN documents.file_type IS '文件类型: pdf / docx / txt / md / html';
COMMENT ON COLUMN documents.file_size IS '文件大小（字节）';
COMMENT ON COLUMN documents.file_path IS 'MinIO存储路径';
COMMENT ON COLUMN documents.file_url IS 'MinIO访问URL';
COMMENT ON COLUMN documents.content IS '文档原始文本内容';
COMMENT ON COLUMN documents.chunk_count IS '文档块数量';
COMMENT ON COLUMN documents.status IS '状态: pending-待处理 processing-处理中 completed-已完成 failed-失败';
COMMENT ON COLUMN documents.error_message IS '错误信息（处理失败时）';
COMMENT ON COLUMN documents.metadata IS '扩展元数据（JSON格式）';
COMMENT ON COLUMN documents.created_at IS '创建时间';
COMMENT ON COLUMN documents.updated_at IS '更新时间';

-- ============================================================================
-- 5. 文档块表 (document_chunks) - 向量检索表
--    结构兼容 AgentScope PgVectorStore（id, embedding, doc_id, chunk_id, content, payload）
-- ============================================================================
CREATE TABLE IF NOT EXISTS document_chunks (
    id              VARCHAR(64)   PRIMARY KEY,                               -- 主键（UUID，兼容AgentScope）
    embedding       vector(1024),                                           -- 向量嵌入（通义千问 text-embedding-v3 1024维）
    doc_id          VARCHAR(64),                                            -- 文档ID（关联documents.id的字符串形式）
    chunk_id        VARCHAR(64),                                            -- 块标识（实测 AgentScope PgVectorStore 以 VARCHAR 写入，2026-09-18 修正）
    content         TEXT,                                                   -- 块文本内容
    payload         JSONB,                                                  -- 扩展元数据（自定义载荷）
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP        -- 创建时间
);

-- 索引
-- 按文档ID查询（用于获取文档下所有块）
CREATE INDEX IF NOT EXISTS idx_document_chunks_doc_id ON document_chunks (doc_id);

-- 向量检索索引（HNSW + 余弦距离，与AgentScope PgVectorStore默认配置一致）
CREATE INDEX IF NOT EXISTS idx_document_chunks_vector
    ON document_chunks
    USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE  document_chunks IS '文档块表（向量检索）';
COMMENT ON COLUMN document_chunks.id IS '主键（UUID，兼容AgentScope PgVectorStore）';
COMMENT ON COLUMN document_chunks.embedding IS '向量嵌入（通义千问 text-embedding-v3 1024维）';
COMMENT ON COLUMN document_chunks.doc_id IS '文档ID（关联documents表）';
COMMENT ON COLUMN document_chunks.chunk_id IS '块序号（同一文档内的块序号）';
COMMENT ON COLUMN document_chunks.content IS '块文本内容';
COMMENT ON COLUMN document_chunks.payload IS '扩展元数据（JSON: 可包含文件类型、来源、标题等）';
COMMENT ON COLUMN document_chunks.created_at IS '创建时间';

-- ============================================================================
-- 初始化：创建默认管理员用户（密码: admin123，BCrypt加密）
-- ============================================================================
INSERT INTO users (username, password, nickname, role, status)
SELECT 'admin', '$2a$10$fVs0gHHOT/aJctFwfzit0efOZI8xknzWUJV0dxFE7M7hdCUlgj3ea', '管理员', 'admin', 1
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');

-- ============================================================================
-- 6. Elasticsearch 索引说明（非 SQL 脚本，通过 ES API 创建）
-- ============================================================================
-- ES 索引: document_chunks_fulltext
-- 用途: RAG 全文检索（BM25），与 pgvector 向量检索组成双路检索
--
-- 索引结构:
--   - content:   text (IK 分词器，ik_max_word 索引 / ik_smart 搜索)
--   - embedding:  dense_vector (1024维, cosine, HNSW)
--   - doc_id:     keyword (文档ID)
--   - chunk_id:   integer (块序号)
--   - title:      text (IK 分词器)
--   - file_type:  keyword (文件类型)
--
-- 初始化方式:
--   1. 启动 ES: docker-compose up -d elasticsearch
--   2. 安装 IK 分词器:
--      docker exec -it travelscope-elasticsearch \
--        ./bin/elasticsearch-plugin install \
--        https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-9.0.2.zip
--   3. 重启 ES: docker-compose restart elasticsearch
--   4. 创建索引: bash src/main/resources/elasticsearch/init-index.sh
--      或手动:
--        curl -X PUT "http://localhost:9200/document_chunks_fulltext" \
--          -H 'Content-Type: application/json' \
--          -d @src/main/resources/elasticsearch/index-mapping.json
--
-- RAG 双路检索架构:
--   ┌──────────────────────────────────────────┐
--   │              用户查询                      │
--   └──────────┬───────────────────┬───────────┘
--              │                   │
--    ┌─────────▼──────┐  ┌────────▼─────────┐
--    │ 向量检索 (pgvector)│  │ 全文检索 (ES BM25)│
--    │ kNN 向量相似度    │  │ IK 中文分词匹配   │
--    └─────────┬──────┘  └────────┬─────────┘
--              │                   │
--    ┌─────────▼───────────────────▼─────────┐
--    │        结果融合 (RRF / 加权分数)        │
--    └───────────────────┬───────────────────┘
--                        │
--                ┌───────▼───────┐
--                │  最终 Top-K   │
--                └───────────────┘
