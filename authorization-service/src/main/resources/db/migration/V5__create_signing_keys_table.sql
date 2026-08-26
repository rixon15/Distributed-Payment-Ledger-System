create table if not exists signing_keys
(
    key_id              varchar(255) primary key,
    wrapped_private_key bytea                    not null,
    status              varchar(20)              not null
        check (status in ('SIGNING', 'VERIFY_ONLY', 'RETIRED')),
    created_at          timestamp with time zone not null,
    retire_at           timestamp with time zone
);

create unique index if not exists idx_signing_keys_one_signing
    on signing_keys (status)
    where status = 'SIGNING';

create index if not exists idx_signing_keys_status_retire_at
    on signing_keys (status, retire_at);