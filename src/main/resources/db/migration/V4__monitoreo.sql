-- Fase 4: archivos (fotos y PDF), reportes de nivel y alertas.

CREATE TABLE archivo (
    id               uuid PRIMARY KEY,
    empresa_id       uuid         NOT NULL REFERENCES empresa (id),
    tipo             varchar(12)  NOT NULL CHECK (tipo IN ('PDF_ORDEN', 'FOTO')),
    ruta             varchar(500) NOT NULL,
    mime             varchar(100) NOT NULL,
    tamano           bigint       NOT NULL CHECK (tamano > 0),
    sha256           varchar(64)  NOT NULL,
    nombre_original  varchar(255),
    subido_por       uuid         NOT NULL REFERENCES usuario (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT archivo_id_empresa_ux UNIQUE (id, empresa_id)
);

-- La foto de un movimiento debe ser un archivo de la misma empresa.
ALTER TABLE movimiento
    ADD CONSTRAINT movimiento_foto_fk FOREIGN KEY (foto_id, empresa_id) REFERENCES archivo (id, empresa_id);

CREATE TABLE reporte_nivel (
    id                  uuid PRIMARY KEY,
    empresa_id          uuid         NOT NULL,
    establecimiento_id  uuid         NOT NULL,
    client_uuid         uuid         NOT NULL UNIQUE,
    producto_id         uuid         NOT NULL,
    ubicacion_id        uuid         NOT NULL,
    nivel_pct           integer      NOT NULL CHECK (nivel_pct IN (0, 25, 50, 75, 100)),
    foto_id             uuid,
    usuario_id          uuid         NOT NULL REFERENCES usuario (id),
    ocurrido_en         timestamptz  NOT NULL,
    registrado_en       timestamptz  NOT NULL,
    nota                varchar(500),
    -- Ganchos para la verificación del nivel por foto con IA (fase 2 del hackathon).
    verificacion_ia     varchar(12)  NOT NULL DEFAULT 'NO_APLICA'
        CHECK (verificacion_ia IN ('NO_APLICA', 'PENDIENTE', 'COINCIDE', 'DIFIERE', 'ERROR')),
    nivel_ia            integer      CHECK (nivel_ia BETWEEN 0 AND 100),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT reporte_producto_fk FOREIGN KEY (producto_id, establecimiento_id) REFERENCES producto (id, establecimiento_id),
    CONSTRAINT reporte_ubicacion_fk FOREIGN KEY (ubicacion_id, establecimiento_id) REFERENCES ubicacion (id, establecimiento_id),
    CONSTRAINT reporte_foto_fk FOREIGN KEY (foto_id, empresa_id) REFERENCES archivo (id, empresa_id)
);
CREATE INDEX reporte_nivel_producto_ix ON reporte_nivel (producto_id, ubicacion_id, registrado_en DESC);

CREATE TABLE alerta (
    id                  uuid PRIMARY KEY,
    empresa_id          uuid          NOT NULL,
    establecimiento_id  uuid          NOT NULL,
    producto_id         uuid          NOT NULL,
    ubicacion_id        uuid,
    tipo                varchar(16)   NOT NULL CHECK (tipo IN ('STOCK_BAJO', 'SIN_STOCK', 'NIVEL_CRITICO', 'COBERTURA_BAJA')),
    severidad           varchar(5)    NOT NULL CHECK (severidad IN ('MEDIA', 'ALTA')),
    mensaje             varchar(500)  NOT NULL,
    estado              varchar(8)    NOT NULL CHECK (estado IN ('ABIERTA', 'ATENDIDA')),
    creada_en           timestamptz   NOT NULL,
    atendida_por        uuid          REFERENCES usuario (id),
    atendida_en         timestamptz,
    -- AUTOMATICO: la condición desapareció. MANUAL: un usuario la marcó como atendida.
    cierre              varchar(10)   CHECK (cierre IN ('AUTOMATICO', 'MANUAL')),
    nota_atencion       varchar(500),
    -- Una alerta cerrada a mano no se reabre hasta que la condición desaparezca al menos una vez.
    rearmada            boolean       NOT NULL DEFAULT false,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT alerta_producto_fk FOREIGN KEY (producto_id, establecimiento_id) REFERENCES producto (id, establecimiento_id),
    CONSTRAINT alerta_ubicacion_fk FOREIGN KEY (ubicacion_id, establecimiento_id) REFERENCES ubicacion (id, establecimiento_id),
    CONSTRAINT alerta_estado_ck CHECK ((estado = 'ATENDIDA') = (atendida_en IS NOT NULL AND cierre IS NOT NULL))
);
-- Solo una alerta abierta por (tipo, producto, ubicación).
CREATE UNIQUE INDEX alerta_abierta_ux ON alerta (tipo, producto_id, coalesce(ubicacion_id, '00000000-0000-0000-0000-000000000000'))
    WHERE estado = 'ABIERTA';
CREATE INDEX alerta_establecimiento_ix ON alerta (establecimiento_id, estado, creada_en DESC);
CREATE INDEX alerta_clave_ix ON alerta (tipo, producto_id, ubicacion_id, creada_en DESC);
