create table if not exists users
(
    id                      uuid primary key,
    username                varchar(100)             not null unique,
    password_hash           varchar(255)             not null,
    enabled                 boolean                  not null,
    account_non_locked      boolean                  not null,
    account_non_expired     boolean                  not null,
    credentials_non_expired boolean                  not null,
    created_at              timestamp with time zone not null,
    updated_at              timestamp with time zone not null
);

create index if not exists idx_users_username
    on users (username);