CREATE ROLE instance_writer LOGIN PASSWORD 'writer';
CREATE SCHEMA instance_test AUTHORIZATION instance_writer;
CREATE SCHEMA history_test;

CREATE TABLE history_test.instance_received_offset
(
    topic_name text NOT NULL,
    partition_id integer NOT NULL CHECK (partition_id >= 0),
    next_offset bigint NOT NULL CHECK (next_offset >= 0),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cluster_id text,
    topic_id uuid,
    PRIMARY KEY (topic_name, partition_id),
    CHECK (btrim(cluster_id) <> ''),
    CHECK (topic_id <> '00000000-0000-0000-0000-000000000000'),
    CHECK ((cluster_id IS NULL) = (topic_id IS NULL))
);

GRANT USAGE ON SCHEMA history_test TO instance_writer;
GRANT SELECT, INSERT, UPDATE ON TABLE history_test.instance_received_offset TO instance_writer;
