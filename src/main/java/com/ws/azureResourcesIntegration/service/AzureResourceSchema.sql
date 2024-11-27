CREATE TABLE public.azure_vm
(
    id                    BIGSERIAL    NOT NULL,
    vm_id                 varchar(255) NOT NULL,
    vm_name               varchar(255) NULL,
    vm_size               varchar(255) NULL,
    os_type               varchar(255) NULL,
    vm_state              varchar(255) NULL,
    availability_zone     varchar(255) NULL,
    location              varchar(255) NULL,
    resource_group        varchar(255) NULL,
    network_interface_ids jsonb NULL,
    private_ip_address    varchar(255) NULL,
    public_ip_address     varchar(255) NULL,
    os_disk_id            varchar(255) NULL,
    created_date          timestamp NULL,
    tenant_name           varchar(255) NULL,
    is_managed_instance   boolean DEFAULT false NULL,
    vm_type               varchar(255) NULL,
    CONSTRAINT azure_vm_pkey PRIMARY KEY (id)
);



CREATE TABLE public.azure_nsg_security_groups
(
    id                        BIGSERIAL    NOT NULL,
    nsg_id                    varchar(255) NOT NULL,
    nsg_name                  varchar(255) NULL,
    nsg_type                  varchar(255) NULL,
    nsg_location              varchar(255) NULL,
    nsg_rule_count            int     DEFAULT 0 NULL,
    associated_with_vm        boolean DEFAULT false NULL,
    associated_with_nic       boolean DEFAULT false NULL,
    associated_resource_group varchar(255) NULL,
    vm_or_nic_id              varchar(255) NULL,
    tenant_name               varchar(255) NULL,
    created_date              timestamp NULL,
    CONSTRAINT azure_nsg_security_groups_pkey PRIMARY KEY (id)
);


CREATE TABLE public.azure_servers
(
    id             BIGSERIAL    NOT NULL,
    server_id      varchar(255) NOT NULL,
    server_name    varchar(255) NOT NULL,
    server_type    varchar(255) NULL,
    server_version varchar(255) NULL,
    region         varchar(255) NULL,
    resource_group varchar(255) NULL,
    created_date   timestamp NULL,
    status         varchar(255) NULL,
    tenant_name    varchar(255) NULL,
    CONSTRAINT azure_servers_pkey PRIMARY KEY (id)
);


CREATE TABLE public.azure_databases
(
    id               BIGSERIAL    NOT NULL,
    database_id      varchar(255) NOT NULL,
    database_name    varchar(255) NOT NULL,
    server_id        varchar(255) NOT NULL,
    database_type    varchar(255) NULL,
    version          varchar(255) NULL,
    status           varchar(255) NULL,
    size_in_gb       int NULL,
    last_backup_time timestamp NULL,
    tenant_name      varchar(255) NULL,
    created_date     timestamp NULL,
    CONSTRAINT azure_databases_pkey PRIMARY KEY (id),
    CONSTRAINT fk_server FOREIGN KEY (server_id) REFERENCES public.azure_servers (id)
);

CREATE TABLE public.azure_storage
(
    id                     BIGSERIAL    NOT NULL,
    storage_account_id     varchar(255) NOT NULL,
    storage_account_name   varchar(255) NOT NULL,
    container_name         varchar(255) NULL,
    blob_name              varchar(255) NULL,
    container_type         varchar(255) NULL,
    blob_type              varchar(255) NULL,
    blob_size              bigint NULL,
    public_access          varchar(255) NULL,
    storage_account_region varchar(255) NULL,
    created_date           timestamp NULL,
    last_modified          timestamp NULL,
    status                 varchar(255) NULL,
    tenant_name            varchar(255) NULL,
    CONSTRAINT azure_storage_pkey PRIMARY KEY (id)
);


CREATE TABLE public.azure_role_definitions
(
    role_definition_id VARCHAR(255) NOT NULL,
    created_date       VARCHAR(255) NULL,
    is_attachable      BOOLEAN NULL,
    role_arn           VARCHAR(255) NULL,
    role_name          VARCHAR(255) NULL,
    description        VARCHAR(255) NULL,
    permissions        JSONB NULL,
    tenant_name        VARCHAR(255) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE public.azure_policies
(
    id                  BIGSERIAL    NOT NULL,
    policy_id           VARCHAR(255) NOT NULL,
    policy_display_name VARCHAR(255) NULL,
    policy_type         VARCHAR(255) NULL,
    policy_version      VARCHAR(255) NULL,
    policy_description  VARCHAR(255) NULL,
    is_custom           BOOLEAN DEFAULT FALSE NULL,
    rules               JSONB NULL,
    PRIMARY KEY (id)
);

CREATE TABLE public.azure_role_assignments
(
    id                 BIGSERIAL    NOT NULL,
    role_assignment_id VARCHAR(255) NULL,
    role_definition_id VARCHAR(255) NOT NULL,
    principal_id       VARCHAR(255) NULL,
    principal_type     VARCHAR(255) NULL,
    scope              VARCHAR(255) NULL,
    start_date         TIMESTAMP NULL,
    end_date           TIMESTAMP NULL,
    tenant_name        VARCHAR(255) NULL,
    is_managed         BOOLEAN DEFAULT FALSE NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_role_definition FOREIGN KEY (role_definition_id) REFERENCES public.azure_role_definitions (role_definition_id)
);


CREATE TABLE public.azure_inline_role_permissions
(
    id                 BIGSERIAL    NOT NULL,
    role_definition_id VARCHAR(255) NOT NULL,
    permission_action  VARCHAR(255) NULL,
    policy_name        VARCHAR(255) NULL,
    resource_type      VARCHAR(255) NULL,
    PRIMARY KEY (role_definition_id, permission_action)
);


CREATE TABLE public.azure_inline_policies
(
    id             BIGSERIAL    NOT NULL,
    role_id        varchar(255) NOT NULL,
    policy_name    varchar(255) NULL,
    description    varchar(255) NULL,
    permissions    jsonb NULL,
    scope          varchar(255) NULL,
    is_custom_role bool DEFAULT false NULL,
    tenant_name    varchar(255) NULL,
    created_date   timestamp NULL,
    CONSTRAINT azure_inline_policies_pkey PRIMARY KEY (id),
    CONSTRAINT fk_role_id FOREIGN KEY (role_id) REFERENCES public.azure_roles (role_id)
);



CREATE TABLE public.azure_role_permissions
(
    id                 BIGSERIAL    NOT NULL,
    role_definition_id VARCHAR(255) NOT NULL,
    permission_action  VARCHAR(255) NULL,
    policy_id          VARCHAR(255) NULL,
    resource_type      VARCHAR(255) NULL,
    PRIMARY KEY (role_definition_id, permission_action),
    CONSTRAINT fk_role_definition FOREIGN KEY (role_definition_id) REFERENCES public.azure_role_definitions (role_definition_id)
);





