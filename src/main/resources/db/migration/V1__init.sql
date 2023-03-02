create table instance_object
(
    id                            bigserial not null,
    instance_object_collection_id int8,
    primary key (id)
);
create table instance_object_value_per_key
(
    instance_object_id int8         not null,
    value              varchar(255),
    key                varchar(255) not null,
    primary key (instance_object_id, key)
);
create table instance_object_collection
(
    id                 bigserial not null,
    instance_object_id int8,
    key                varchar(255),
    primary key (id)
);
alter table instance_object
    add constraint FKp6f8ogjmuv3trkdxfp1eb7so0 foreign key (instance_object_collection_id) references instance_object_collection;
alter table instance_object_value_per_key
    add constraint FKjw2gv69q6faqejinh37u6te94 foreign key (instance_object_id) references instance_object;
alter table instance_object_collection
    add constraint FK7bq0pdfgejo5hxcakb3vxq079 foreign key (instance_object_id) references instance_object;
