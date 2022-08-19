create table document (id  bigserial not null, base64 varchar(255), type varchar(255), uri varchar(255), instance_id int8, primary key (id));
create table instance (id  bigserial not null, source_application_instance_uri varchar(255), primary key (id));
create table instance_field (id  bigserial not null, key varchar(255), value varchar(255), instance_id int8, primary key (id));
alter table document add constraint FK7lj6kapjrnq817tu3cqaddb68 foreign key (instance_id) references instance;
alter table instance_field add constraint FK4i72imie499sucte7k6637cnj foreign key (instance_id) references instance;
