-- Managed operation log storage for slot 1. Execute only on that slot's designated primary.
CREATE TABLE egon_cola_operation_log_t1_b0 (
    id bigint NOT NULL CHECK (id > 0),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    operation_id varchar(64) NOT NULL,
    schema_version smallint NOT NULL CHECK (schema_version = 1),
    source_service varchar(128) NOT NULL,
    module_code varchar(64) NOT NULL,
    action_code varchar(96) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id varchar(128),
    biz_code varchar(128),
    namespace_code varchar(128),
    env varchar(32),
    app_code varchar(128),
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    actor_id varchar(512) NOT NULL,
    actor_name varchar(128),
    trigger_type varchar(16) NOT NULL CHECK (trigger_type IN ('HTTP', 'RPC', 'MESSAGE', 'JOB')),
    phase varchar(16) NOT NULL CHECK (phase IN ('COMMAND', 'EXECUTION')),
    outcome varchar(32) NOT NULL
        CHECK (outcome IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS', 'UNKNOWN', 'REJECTED', 'ROLLED_BACK')),
    reason_code varchar(96),
    http_method varchar(16),
    request_url varchar(2048),
    route_pattern varchar(512),
    handler_method varchar(1024) NOT NULL,
    trace_id varchar(64),
    request_id varchar(128),
    occurred_at timestamptz(6) NOT NULL,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    details jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 4096),
    create_user_id varchar(512) NOT NULL,
    create_time timestamptz(6) NOT NULL,
    update_user_id varchar(512) NOT NULL,
    update_time timestamptz(6) NOT NULL,
    version bigint NOT NULL CHECK (version = 0),
    deleted_at timestamp(6) without time zone CHECK (deleted_at IS NULL),
    CONSTRAINT pk_operation_log_t1_b0 PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_operation_log_page_t1_b0
    ON egon_cola_operation_log_t1_b0 (tenant_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE egon_cola_operation_log_receipt_t1_b0 (
    id bigint NOT NULL CHECK (id > 0),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    occurred_at timestamptz(6) NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    create_user_id varchar(512) NOT NULL,
    create_time timestamptz(6) NOT NULL,
    update_user_id varchar(512) NOT NULL,
    update_time timestamptz(6) NOT NULL,
    version bigint NOT NULL CHECK (version = 0),
    deleted_at timestamp(6) without time zone CHECK (deleted_at IS NULL),
    CONSTRAINT pk_operation_log_receipt_t1_b0 PRIMARY KEY (tenant_id, id)
);

CREATE INDEX ix_operation_log_receipt_gc_t1_b0
    ON egon_cola_operation_log_receipt_t1_b0 (create_time, tenant_id, id);

CREATE TABLE egon_cola_operation_log_t1_b1 (
    id bigint NOT NULL CHECK (id > 0),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    operation_id varchar(64) NOT NULL,
    schema_version smallint NOT NULL CHECK (schema_version = 1),
    source_service varchar(128) NOT NULL,
    module_code varchar(64) NOT NULL,
    action_code varchar(96) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id varchar(128),
    biz_code varchar(128),
    namespace_code varchar(128),
    env varchar(32),
    app_code varchar(128),
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    actor_id varchar(512) NOT NULL,
    actor_name varchar(128),
    trigger_type varchar(16) NOT NULL CHECK (trigger_type IN ('HTTP', 'RPC', 'MESSAGE', 'JOB')),
    phase varchar(16) NOT NULL CHECK (phase IN ('COMMAND', 'EXECUTION')),
    outcome varchar(32) NOT NULL
        CHECK (outcome IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS', 'UNKNOWN', 'REJECTED', 'ROLLED_BACK')),
    reason_code varchar(96),
    http_method varchar(16),
    request_url varchar(2048),
    route_pattern varchar(512),
    handler_method varchar(1024) NOT NULL,
    trace_id varchar(64),
    request_id varchar(128),
    occurred_at timestamptz(6) NOT NULL,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    details jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 4096),
    create_user_id varchar(512) NOT NULL,
    create_time timestamptz(6) NOT NULL,
    update_user_id varchar(512) NOT NULL,
    update_time timestamptz(6) NOT NULL,
    version bigint NOT NULL CHECK (version = 0),
    deleted_at timestamp(6) without time zone CHECK (deleted_at IS NULL),
    CONSTRAINT pk_operation_log_t1_b1 PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_operation_log_page_t1_b1
    ON egon_cola_operation_log_t1_b1 (tenant_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE egon_cola_operation_log_receipt_t1_b1 (
    id bigint NOT NULL CHECK (id > 0),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    occurred_at timestamptz(6) NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    create_user_id varchar(512) NOT NULL,
    create_time timestamptz(6) NOT NULL,
    update_user_id varchar(512) NOT NULL,
    update_time timestamptz(6) NOT NULL,
    version bigint NOT NULL CHECK (version = 0),
    deleted_at timestamp(6) without time zone CHECK (deleted_at IS NULL),
    CONSTRAINT pk_operation_log_receipt_t1_b1 PRIMARY KEY (tenant_id, id)
);

CREATE INDEX ix_operation_log_receipt_gc_t1_b1
    ON egon_cola_operation_log_receipt_t1_b1 (create_time, tenant_id, id);


DO $partitions$
DECLARE
    parent_name text;
    month_start timestamp := date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
    lower_bound timestamp;
    upper_bound timestamp;
    month_offset integer;
BEGIN
    FOREACH parent_name IN ARRAY ARRAY['egon_cola_operation_log_t1_b0', 'egon_cola_operation_log_t1_b1']
    LOOP
        FOR month_offset IN 0..2 LOOP
            lower_bound := month_start + make_interval(months => month_offset);
            upper_bound := lower_bound + interval '1 month';
            EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                parent_name || '_' || to_char(lower_bound, 'YYYYMM'), parent_name,
                to_char(lower_bound, 'YYYY-MM-DD"T"HH24:MI:SS') || 'Z',
                to_char(upper_bound, 'YYYY-MM-DD"T"HH24:MI:SS') || 'Z');
        END LOOP;
    END LOOP;
END
$partitions$;
