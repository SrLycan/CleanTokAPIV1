-- Fase 1: empresas, usuarios y refresh tokens.

CREATE TABLE empresa (
    id          uuid PRIMARY KEY,
    nombre      varchar(200) NOT NULL,
    ruc         varchar(13)  NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE usuario (
    id             uuid PRIMARY KEY,
    empresa_id     uuid REFERENCES empresa (id),
    nombre         varchar(200) NOT NULL,
    email          varchar(254) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    rol            varchar(20)  NOT NULL CHECK (rol IN ('SUPER_ADMIN', 'ADMIN_EMPRESA', 'ENCARGADO')),
    activo         boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    -- Solo el SUPER_ADMIN vive fuera de una empresa.
    CONSTRAINT usuario_empresa_ck CHECK ((rol = 'SUPER_ADMIN') = (empresa_id IS NULL))
);
CREATE UNIQUE INDEX usuario_email_ux ON usuario (lower(email));
CREATE INDEX usuario_empresa_ix ON usuario (empresa_id);

CREATE TABLE refresh_token (
    id               uuid PRIMARY KEY,
    usuario_id       uuid        NOT NULL REFERENCES usuario (id),
    familia_id       uuid        NOT NULL,
    token_hash       varchar(64) NOT NULL UNIQUE,
    expira_en        timestamptz NOT NULL,
    revocado_en      timestamptz,
    reemplazado_por  uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_token_familia_ix ON refresh_token (familia_id);
