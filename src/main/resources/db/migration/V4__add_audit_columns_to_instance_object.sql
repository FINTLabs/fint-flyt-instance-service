alter table instance_object
    alter column created_at drop default;

alter table instance_object
    alter column created_at type timestamptz using created_at at time zone 'UTC';

alter table instance_object
    add column created_by jsonb not null default '{"type":"UNKNOWN"}'::jsonb;
