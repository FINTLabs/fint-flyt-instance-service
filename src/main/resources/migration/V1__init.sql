create table data (id  bigserial not null, format varchar(255), uri varchar(255), instance_id int8, primary key (id));
create table instance_fields (id  bigserial not null, name varchar(255), value varchar(255), instance_id int8, primary key (id));
create table instances (id  bigserial not null, form_id varchar(255), uri varchar(255), primary key (id));
alter table data add constraint FK33ak2u06upjbfv9smwfs1h9vi foreign key (instance_id) references instances;
alter table instance_fields add constraint FKgctjgktetf3ycbxo9o8wn78gl foreign key (instance_id) references instances;
