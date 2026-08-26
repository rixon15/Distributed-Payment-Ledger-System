insert into users (
    id,
    username,
    password_hash,
    enabled,
    account_non_locked,
    account_non_expired,
    credentials_non_expired,
    created_at,
    updated_at
)
values (
           '11111111-1111-1111-1111-111111111111',
           'demo-user',
           '{noop}demo-password',
           true,
           true,
           true,
           true,
           now(),
           now()
       )
on conflict (username) do nothing;