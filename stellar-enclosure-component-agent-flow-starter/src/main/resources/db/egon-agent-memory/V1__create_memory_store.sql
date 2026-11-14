-- Install explicitly into the approved Memory schema using the host's migration role.
CREATE TABLE egon_agent_memory.scope (
    scope_hash char(64) COLLATE "C" NOT NULL,
    canonical_scope jsonb NOT NULL,
    authority_epoch bigint NOT NULL,
    storage_ref varchar(128) COLLATE "C" NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    high_water_revision bigint NOT NULL,
    created_at timestamptz(6) NOT NULL,
    updated_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_memory_scope_pkey PRIMARY KEY (scope_hash),
    CONSTRAINT memory_scope_identity CHECK (
        scope_hash ~ '^[0-9a-f]{64}$' AND authority_epoch > 0 AND high_water_revision >= 0 AND updated_at >= created_at
        AND storage_ref = ANY (ARRAY['LEGACY','COMPONENT']) AND state = ANY (ARRAY['ACTIVE','FROZEN'])
        AND (state <> 'FROZEN' OR storage_ref = 'LEGACY')
    ),
    CONSTRAINT memory_scope_json CHECK (
        jsonb_typeof(canonical_scope) = 'object' AND octet_length(canonical_scope::text) <= 4096
        AND canonical_scope ?& ARRAY['namespace','tenantKey','retentionScope','principalKey','instanceKey','sessionKey','hostScopeKey']
        AND canonical_scope - ARRAY['namespace','tenantKey','retentionScope','principalKey','instanceKey','sessionKey','hostScopeKey'] = '{}'::jsonb
        AND jsonb_typeof(canonical_scope->'namespace') = 'string' AND jsonb_typeof(canonical_scope->'tenantKey') = 'string'
        AND char_length(canonical_scope->>'namespace') > 0 AND char_length(canonical_scope->>'namespace') <= 128
        AND char_length(canonical_scope->>'tenantKey') > 0 AND char_length(canonical_scope->>'tenantKey') <= 128
        AND (
            (canonical_scope->>'retentionScope' = 'HOST' AND jsonb_typeof(canonical_scope->'hostScopeKey') = 'string'
                AND canonical_scope->>'hostScopeKey' ~ '^[!-~]+$' AND char_length(canonical_scope->>'hostScopeKey') <= 2048
                AND canonical_scope->'principalKey' = 'null'::jsonb AND canonical_scope->'instanceKey' = 'null'::jsonb
                AND canonical_scope->'sessionKey' = 'null'::jsonb)
            OR
            (storage_ref = 'COMPONENT' AND canonical_scope->'hostScopeKey' = 'null'::jsonb
                AND jsonb_typeof(canonical_scope->'principalKey') = 'string'
                AND char_length(canonical_scope->>'principalKey') > 0 AND char_length(canonical_scope->>'principalKey') <= 128
                AND (
                    (canonical_scope->>'retentionScope' = 'USER' AND canonical_scope->'instanceKey' = 'null'::jsonb
                        AND canonical_scope->'sessionKey' = 'null'::jsonb)
                    OR
                    (jsonb_typeof(canonical_scope->'instanceKey') = 'string'
                        AND char_length(canonical_scope->>'instanceKey') > 0 AND char_length(canonical_scope->>'instanceKey') <= 128
                        AND ((canonical_scope->>'retentionScope' = 'INSTANCE' AND canonical_scope->'sessionKey' = 'null'::jsonb)
                            OR (canonical_scope->>'retentionScope' = 'SESSION' AND jsonb_typeof(canonical_scope->'sessionKey') = 'string'
                                AND char_length(canonical_scope->>'sessionKey') > 0 AND char_length(canonical_scope->>'sessionKey') <= 128)))
                ))
        ) IS TRUE
    )
);

CREATE TABLE egon_agent_memory.revision (
    scope_hash char(64) COLLATE "C" NOT NULL,
    fact_id varchar(128) COLLATE "C" NOT NULL,
    revision bigint NOT NULL,
    scope_revision bigint NOT NULL,
    content_profile varchar(16) COLLATE "C" NOT NULL,
    content jsonb,
    provenance jsonb,
    state varchar(16) COLLATE "C" NOT NULL,
    confirmed boolean NOT NULL,
    effective_at timestamptz(6) NOT NULL,
    expires_at timestamptz(6),
    redacted_at timestamptz(6),
    host_metadata jsonb,
    native_metadata jsonb,
    content_sha256 char(64) COLLATE "C" NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_memory_revision_pkey PRIMARY KEY (scope_hash,fact_id,revision),
    CONSTRAINT memory_scope_revision_uq UNIQUE (scope_hash,scope_revision),
    CONSTRAINT memory_revision_scope_fk FOREIGN KEY (scope_hash) REFERENCES egon_agent_memory.scope(scope_hash) ON DELETE NO ACTION,
    CONSTRAINT memory_revision_identity CHECK (
        fact_id <> '' AND fact_id !~ '[[:cntrl:]]' AND revision > 0 AND scope_revision > 0 AND content_sha256 ~ '^[0-9a-f]{64}$'
        AND (expires_at IS NULL OR expires_at > effective_at)
    ),
    CONSTRAINT memory_revision_content CHECK (
        (state = ANY (ARRAY['CANDIDATE','ACTIVE','SUPERSEDED']) AND content IS NOT NULL
            AND jsonb_typeof(content) = 'object' AND octet_length(content::text) <= 131072)
        OR (state = ANY (ARRAY['FORGOTTEN','EXPIRED','DELETED']) AND content IS NULL AND provenance IS NULL)
    ),
    CONSTRAINT memory_revision_confirmation CHECK ((state <> 'ACTIVE' OR confirmed) AND (state <> 'CANDIDATE' OR NOT confirmed)),
    CONSTRAINT memory_revision_profile CHECK (
        (content_profile = 'NATIVE' AND host_metadata IS NULL AND redacted_at IS NULL AND native_metadata IS NOT NULL
            AND state = ANY (ARRAY['ACTIVE','CANDIDATE','DELETED'])
            AND (state = 'DELETED' OR CASE WHEN jsonb_typeof(provenance) = 'array'
                THEN jsonb_array_length(provenance) <= 32 AND octet_length(provenance::text) <= 131072 ELSE false END))
        OR
        (content_profile = 'HOST_V1' AND native_metadata IS NULL AND provenance IS NULL AND expires_at IS NOT NULL AND host_metadata IS NOT NULL
            AND state = ANY (ARRAY['CANDIDATE','ACTIVE','SUPERSEDED','FORGOTTEN','EXPIRED'])
            AND ((state = ANY (ARRAY['FORGOTTEN','EXPIRED']) AND redacted_at IS NOT NULL AND host_metadata->'source' = 'null'::jsonb)
                OR (state <> ALL (ARRAY['FORGOTTEN','EXPIRED']) AND redacted_at IS NULL)))
    ),
    CONSTRAINT memory_revision_native_metadata CHECK (
        native_metadata IS NULL OR (
            jsonb_typeof(native_metadata) = 'object' AND octet_length(native_metadata::text) <= 4096
            AND native_metadata ?& ARRAY['sensitivity','retentionPolicyRef','protectedMutation','protectedReceipt']
            AND native_metadata - ARRAY['sensitivity','retentionPolicyRef','protectedMutation','protectedReceipt'] = '{}'::jsonb
            AND jsonb_typeof(native_metadata->'sensitivity') = 'string'
            AND char_length(native_metadata->>'sensitivity') > 0 AND char_length(native_metadata->>'sensitivity') <= 128
            AND native_metadata->>'sensitivity' !~ '[[:cntrl:]]'
            AND jsonb_typeof(native_metadata->'retentionPolicyRef') = 'string'
            AND char_length(native_metadata->>'retentionPolicyRef') > 0 AND char_length(native_metadata->>'retentionPolicyRef') <= 256
            AND native_metadata->>'retentionPolicyRef' !~ '[[:cntrl:]]'
            AND jsonb_typeof(native_metadata->'protectedMutation') = 'boolean'
            AND jsonb_typeof(native_metadata->'protectedReceipt') = 'boolean'
            AND (native_metadata->'protectedMutation' = 'false'::jsonb OR native_metadata->'protectedReceipt' = 'true'::jsonb)
            AND (native_metadata->'protectedMutation' = 'true'::jsonb
                OR (native_metadata->>'sensitivity' = 'INTERNAL' AND native_metadata->>'retentionPolicyRef' = 'default'
                    AND confirmed AND expires_at IS NULL))
        ) IS TRUE
    ),
    CONSTRAINT memory_revision_host_metadata CHECK (
        host_metadata IS NULL OR (
            jsonb_typeof(host_metadata) = 'object' AND octet_length(host_metadata::text) <= 32768
            AND host_metadata ?& ARRAY['schemaVersion','source','sourceEvidenceSha256','sourceActorSha256','confidence',
                'sensitivity','retentionPolicyRef','indexModelRef','supersedesRevision','reasonCode']
            AND host_metadata - ARRAY['schemaVersion','source','sourceEvidenceSha256','sourceActorSha256','confidence',
                'sensitivity','retentionPolicyRef','indexModelRef','supersedesRevision','reasonCode'] = '{}'::jsonb
            AND host_metadata->'schemaVersion' = '1'::jsonb
            AND jsonb_typeof(host_metadata->'source') = ANY (ARRAY['object','null'])
            AND host_metadata->>'sensitivity' = ANY (ARRAY['PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED'])
        ) IS TRUE
    )
);

CREATE TABLE egon_agent_memory.fact (
    scope_hash char(64) COLLATE "C" NOT NULL,
    fact_id varchar(128) COLLATE "C" NOT NULL,
    fact_type varchar(16) COLLATE "C" NOT NULL,
    created_at timestamptz(6) NOT NULL,
    current_revision bigint NOT NULL,
    updated_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_memory_fact_pkey PRIMARY KEY (scope_hash,fact_id),
    CONSTRAINT memory_fact_scope_fk FOREIGN KEY (scope_hash) REFERENCES egon_agent_memory.scope(scope_hash) ON DELETE NO ACTION,
    CONSTRAINT memory_fact_revision_fk FOREIGN KEY (scope_hash,fact_id,current_revision)
        REFERENCES egon_agent_memory.revision(scope_hash,fact_id,revision) ON DELETE NO ACTION,
    CONSTRAINT memory_fact_head CHECK (
        fact_id <> '' AND fact_id !~ '[[:cntrl:]]' AND fact_type = ANY (ARRAY['FACT','PREFERENCE','EXPERIENCE'])
        AND current_revision > 0 AND updated_at >= created_at
    )
);

CREATE INDEX memory_fact_scope_type_id_idx ON egon_agent_memory.fact (scope_hash,fact_type,fact_id);

CREATE TABLE egon_agent_memory.receipt (
    scope_hash char(64) COLLATE "C" NOT NULL,
    command_namespace varchar(1280) COLLATE "C" NOT NULL,
    command_id varchar(128) COLLATE "C" NOT NULL,
    request_sha256 char(64) COLLATE "C" NOT NULL,
    operation varchar(16) COLLATE "C" NOT NULL,
    result_outcome varchar(16) COLLATE "C",
    result_state varchar(16) COLLATE "C",
    fact_id varchar(128) COLLATE "C" NOT NULL,
    result_revision bigint NOT NULL,
    authority_epoch bigint,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT egon_agent_memory_receipt_pkey PRIMARY KEY (scope_hash,command_namespace,command_id),
    CONSTRAINT memory_receipt_scope_fk FOREIGN KEY (scope_hash) REFERENCES egon_agent_memory.scope(scope_hash) ON DELETE NO ACTION,
    CONSTRAINT memory_receipt_revision_fk FOREIGN KEY (scope_hash,fact_id,result_revision)
        REFERENCES egon_agent_memory.revision(scope_hash,fact_id,revision) ON DELETE NO ACTION,
    CONSTRAINT memory_receipt_identity CHECK (
        command_id <> '' AND command_id !~ '[[:cntrl:]]' AND request_sha256 ~ '^[0-9a-f]{64}$' AND result_revision > 0
        AND (authority_epoch IS NULL OR authority_epoch > 0)
    ),
    CONSTRAINT memory_receipt_contract CHECK (
        (command_namespace = 'NATIVE' AND authority_epoch IS NOT NULL AND result_outcome IS NULL AND result_state IS NULL
            AND operation = ANY (ARRAY['UPSERT','DELETE']))
        OR
        (command_namespace ~ '^HOST_V1:[A-Za-z0-9_-]+$' AND result_outcome IS NOT NULL AND result_state IS NOT NULL
            AND ((operation = 'UPSERT' AND result_outcome = ANY (ARRAY['CREATED','DEDUPLICATED'])
                AND result_state = ANY (ARRAY['CANDIDATE','ACTIVE']))
                OR (operation = 'CORRECT' AND result_outcome = 'CORRECTED' AND result_state = 'ACTIVE')
                OR (operation = 'FORGET' AND result_outcome = 'FORGOTTEN' AND result_state = 'FORGOTTEN')))
    )
);
