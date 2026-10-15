-- Fase 2: establecimientos, asignación de encargados y ubicaciones con QR.

-- Permite FKs compuestas (id, empresa_id): la base garantiza que nada cruce de empresa.
ALTER TABLE usuario ADD CONSTRAINT usuario_id_empresa_ux UNIQUE (id, empresa_id);

CREATE TABLE establecimiento (
    id                   uuid PRIMARY KEY,
    empresa_id           uuid          NOT NULL REFERENCES empresa (id),
    nombre               varchar(200)  NOT NULL,
    tipo                 varchar(20)   NOT NULL CHECK (tipo IN ('OFICINA', 'HOSPITAL', 'FABRICA', 'EDUCATIVO', 'OTRO')),
    entidad_contratante  varchar(300),
    direccion            varchar(500),
    lat                  numeric(9, 6) CHECK (lat BETWEEN -90 AND 90),
    lng                  numeric(9, 6) CHECK (lng BETWEEN -180 AND 180),
    area_m2              numeric(12, 2) CHECK (area_m2 > 0),
    activo               boolean       NOT NULL DEFAULT true,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT establecimiento_id_empresa_ux UNIQUE (id, empresa_id)
);
CREATE INDEX establecimiento_empresa_ix ON establecimiento (empresa_id);

CREATE TABLE usuario_establecimiento (
    id                  uuid PRIMARY KEY,
    empresa_id          uuid        NOT NULL,
    usuario_id          uuid        NOT NULL,
    establecimiento_id  uuid        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT usuario_establecimiento_ux UNIQUE (usuario_id, establecimiento_id),
    CONSTRAINT usuario_establecimiento_usuario_fk FOREIGN KEY (usuario_id, empresa_id) REFERENCES usuario (id, empresa_id),
    CONSTRAINT usuario_establecimiento_est_fk FOREIGN KEY (establecimiento_id, empresa_id)
        REFERENCES establecimiento (id, empresa_id) ON DELETE CASCADE
);
CREATE INDEX usuario_establecimiento_est_ix ON usuario_establecimiento (establecimiento_id);

CREATE TABLE ubicacion (
    id                  uuid PRIMARY KEY,
    empresa_id          uuid         NOT NULL,
    establecimiento_id  uuid         NOT NULL,
    nombre              varchar(120) NOT NULL,
    tipo                varchar(10)  NOT NULL CHECK (tipo IN ('BODEGA', 'PISO', 'AREA')),
    orden               integer      NOT NULL DEFAULT 0,
    qr_token            varchar(64)  NOT NULL UNIQUE CHECK (length(qr_token) >= 22),
    activo              boolean      NOT NULL DEFAULT true,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ubicacion_est_fk FOREIGN KEY (establecimiento_id, empresa_id) REFERENCES establecimiento (id, empresa_id),
    CONSTRAINT ubicacion_id_empresa_ux UNIQUE (id, empresa_id),
    -- La bodega nunca se desactiva.
    CONSTRAINT ubicacion_bodega_activa_ck CHECK (tipo <> 'BODEGA' OR activo)
);
-- Exactamente una bodega por establecimiento (la crea la aplicación junto con el establecimiento).
CREATE UNIQUE INDEX ubicacion_bodega_ux ON ubicacion (establecimiento_id) WHERE tipo = 'BODEGA';
CREATE INDEX ubicacion_est_ix ON ubicacion (establecimiento_id, orden);
