-- Borrar un producto es un cambio de estado, no un borrado físico (github-24).
alter table product
    add column status varchar(16) not null default 'ACTIVE';
