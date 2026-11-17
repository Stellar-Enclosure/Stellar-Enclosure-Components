-- Incremental V2 DDL for Path A three-tier memory tables in the approved egon_agent_memory schema.
-- Install with the host's migration role; do not modify V1__create_memory_store.sql or manifest.json.

CREATE TABLE egon_agent_memory.session_message (
    tenant_key varchar(128) COLLATE "C" NOT NULL,
    conversation_id varchar(256) COLLATE "C" NOT NULL,
    message_seq bigint NOT NULL,
    turn_id varchar(64) COLLATE "C" NOT NULL,
    message_type varchar(16) COLLATE "C" NOT NULL,
    content_text text,
    tool_payload jsonb,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_session_message_pkey PRIMARY KEY (tenant_key, conversation_id, message_seq),
    CONSTRAINT chk_session_message_identity CHECK (
        tenant_key <> '' AND tenant_key !~ '[[:cntrl:]]'
        AND conversation_id <> '' AND conversation_id !~ '[[:cntrl:]]'
        AND message_seq >= 1
        AND turn_id <> '' AND turn_id !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_session_message_type CHECK (
        message_type = ANY (ARRAY['USER','ASSISTANT','TOOL','SYSTEM'])
    ),
    CONSTRAINT chk_session_message_payload CHECK (
        (content_text IS NOT NULL OR tool_payload IS NOT NULL)
        AND (tool_payload IS NULL OR (jsonb_typeof(tool_payload) = 'object' AND octet_length(tool_payload::text) <= 262144))
    )
);

CREATE INDEX idx_session_message_turn_lookup
    ON egon_agent_memory.session_message (tenant_key, conversation_id, turn_id, message_seq);

CREATE TABLE egon_agent_memory.text_memory (
    id varchar(64) COLLATE "C" NOT NULL,
    tenant_key varchar(128) COLLATE "C" NOT NULL,
    namespace varchar(128) COLLATE "C" NOT NULL,
    owner_type varchar(32) COLLATE "C" NOT NULL,
    owner_key varchar(256) COLLATE "C" NOT NULL,
    content_text text NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz(6) NOT NULL,
    updated_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_text_memory_pkey PRIMARY KEY (id),
    CONSTRAINT uk_text_memory_owner UNIQUE (tenant_key, namespace, owner_type, owner_key),
    CONSTRAINT chk_text_memory_identity CHECK (
        id <> '' AND id !~ '[[:cntrl:]]'
        AND tenant_key <> '' AND tenant_key !~ '[[:cntrl:]]'
        AND namespace <> '' AND namespace !~ '[[:cntrl:]]'
        AND owner_type = ANY (ARRAY['AGENT','SESSION','CUSTOM'])
        AND owner_key <> '' AND owner_key !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_text_memory_len CHECK (
        char_length(content_text) <= 10000
    ),
    CONSTRAINT chk_text_memory_version CHECK (
        version >= 1 AND updated_at >= created_at
    )
);

CREATE TABLE egon_agent_memory.doc_memory (
    id varchar(128) COLLATE "C" NOT NULL,
    tenant_key varchar(128) COLLATE "C" NOT NULL,
    title varchar(256) NOT NULL,
    content_md text NOT NULL,
    content_sha256 char(64) COLLATE "C",
    deleted boolean NOT NULL,
    updated_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_doc_memory_pkey PRIMARY KEY (tenant_key, id),
    CONSTRAINT chk_doc_memory_identity CHECK (
        id <> '' AND id !~ '[[:cntrl:]]'
        AND tenant_key <> '' AND tenant_key !~ '[[:cntrl:]]'
        AND title !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_doc_memory_content CHECK (
        content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'
    )
);
