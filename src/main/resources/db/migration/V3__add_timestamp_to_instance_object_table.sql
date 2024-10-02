alter table instance_object
    add column created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
