create table instance_element
(
    id                             bigserial not null,
    instance_element_collection_id int8,
    primary key (id)
);
create table instance_element_value_per_key
(
    instance_element_id int8         not null,
    value               varchar(255),
    key                 varchar(255) not null,
    primary key (instance_element_id, key)
);
create table instance_element_collection
(
    id                  bigserial not null,
    instance_element_id int8,
    key                 varchar(255),
    primary key (id)
);
alter table instance_element
    add constraint FKs9kh12p0dtvo39bstomqweyad foreign key (instance_element_collection_id) references instance_element_collection;
alter table instance_element_value_per_key
    add constraint FK5gs4hklv7s7efuf416t4ypbed foreign key (instance_element_id) references instance_element;
alter table instance_element_collection
    add constraint FKdpwfyv0la62q55ad28kunnmhq foreign key (instance_element_id) references instance_element;
