create index if not exists idx_oauth2_authorization_state
    on oauth2_authorization (state);

create index if not exists idx_oauth2_authorization_authorization_code_value
    on oauth2_authorization (authorization_code_value);

create index if not exists idx_oauth2_authorization_access_token_value
    on oauth2_authorization (access_token_value);

create index if not exists idx_oauth2_authorization_refresh_token_value
    on oauth2_authorization (refresh_token_value);

create index if not exists idx_oauth2_authorization_principal_name
    on oauth2_authorization (principal_name);

create index if not exists idx_oauth2_authorization_registered_client_id
    on oauth2_authorization (registered_client_id);

create index if not exists idx_oauth2_authorization_oidc_id_token_value
    on oauth2_authorization (oidc_id_token_value);