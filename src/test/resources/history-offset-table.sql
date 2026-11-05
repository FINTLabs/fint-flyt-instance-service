-- Mirrors production: history-service owns the schema and table (V9 + V10), instance-service only has grants.
CREATE ROLE test_no_fint_flyt_history_service_db;
CREATE SCHEMA test_no_fint_flyt_history_service_db AUTHORIZATION test_no_fint_flyt_history_service_db;

SET ROLE test_no_fint_flyt_history_service_db;
CREATE TABLE test_no_fint_flyt_history_service_db.instance_received_offset
(
    topic_name   text        NOT NULL,
    partition_id integer     NOT NULL CHECK (partition_id >= 0),
    next_offset  bigint      NOT NULL CHECK (next_offset >= 0),
    updated_at   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic_name, partition_id)
);
ALTER TABLE test_no_fint_flyt_history_service_db.instance_received_offset
    ADD COLUMN cluster_id text,
    ADD COLUMN topic_id uuid,
    ADD CONSTRAINT instance_received_cluster_id_not_blank CHECK (btrim(cluster_id) <> ''),
    ADD CONSTRAINT instance_received_topic_id_not_zero CHECK (topic_id <> '00000000-0000-0000-0000-000000000000'),
    ADD CONSTRAINT instance_received_generation_complete CHECK ((cluster_id IS NULL) = (topic_id IS NULL));
RESET ROLE;

CREATE ROLE test_no_fint_flyt_instance_service_db LOGIN PASSWORD 'instance';
GRANT USAGE ON SCHEMA test_no_fint_flyt_history_service_db TO test_no_fint_flyt_instance_service_db;
GRANT SELECT, INSERT, UPDATE ON test_no_fint_flyt_history_service_db.instance_received_offset
    TO test_no_fint_flyt_instance_service_db;
