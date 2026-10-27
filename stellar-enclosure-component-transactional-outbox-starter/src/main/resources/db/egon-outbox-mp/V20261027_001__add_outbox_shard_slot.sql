ALTER TABLE egon_cola_outbox_message
    ADD COLUMN shard_slot integer NOT NULL DEFAULT 0;

ALTER TABLE egon_cola_outbox_message
    ADD CONSTRAINT ck_outbox_shard_slot CHECK (shard_slot >= 0);

ALTER TABLE egon_cola_outbox_message
    ADD CONSTRAINT ck_outbox_message_shard_identity CHECK (
        CASE WHEN length(message_id) > 12 AND substring(message_id, 1, 12) ~ '^s1_[0-9a-f]{8}_$'
            THEN (get_byte(decode(substring(message_id, 4, 8), 'hex'), 0)::bigint * 16777216
                + get_byte(decode(substring(message_id, 4, 8), 'hex'), 1) * 65536
                + get_byte(decode(substring(message_id, 4, 8), 'hex'), 2) * 256
                + get_byte(decode(substring(message_id, 4, 8), 'hex'), 3)) = shard_slot
            ELSE shard_slot = 0
        END
    );

ALTER TABLE egon_cola_outbox_message
    ADD CONSTRAINT ck_outbox_key_shard_identity CHECK (
        idempotency_key IS NULL OR CASE
            WHEN length(idempotency_key) > 12 AND substring(idempotency_key, 1, 12) ~ '^s1_[0-9a-f]{8}_$'
                THEN (get_byte(decode(substring(idempotency_key, 4, 8), 'hex'), 0)::bigint * 16777216
                    + get_byte(decode(substring(idempotency_key, 4, 8), 'hex'), 1) * 65536
                    + get_byte(decode(substring(idempotency_key, 4, 8), 'hex'), 2) * 256
                    + get_byte(decode(substring(idempotency_key, 4, 8), 'hex'), 3)) = shard_slot
            ELSE shard_slot = 0
        END
    );
