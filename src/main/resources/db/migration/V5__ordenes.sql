-- Fase 5: órdenes de compra en PDF y materiales extraídos para revisión.

CREATE TABLE orden_servicio (
    id                     uuid PRIMARY KEY,
    empresa_id             uuid          NOT NULL,
    establecimiento_id     uuid          NOT NULL,
    numero_orden           varchar(40),
    entidad_contratante    varchar(300),
    fecha_emision          date,
    area_m2                numeric(12, 2),
    descripcion_servicio   varchar(1000),
    vigencia_meses         integer,
    archivo_id             uuid          NOT NULL,
    sha256                 varchar(64)   NOT NULL,
    formato_detectado      varchar(12)   CHECK (formato_detectado IN ('SERCOP', 'DESCONOCIDO')),
    metodo_extraccion      varchar(20)   CHECK (metodo_extraccion IN ('PARSER_SERCOP', 'IA', 'PARSER_SERCOP+IA', 'MANUAL')),
    estado                 varchar(20)   NOT NULL CHECK (estado IN ('PROCESANDO', 'PENDIENTE_REVISION', 'CONFIRMADA', 'ERROR')),
    texto_extraido         text,
    error_detalle          varchar(1000),
    avisos                 varchar(4000),
    tiene_capa_texto       boolean,
    permitir_envio_pdf_ia  boolean       NOT NULL DEFAULT false,
    forzar_ia              boolean       NOT NULL DEFAULT false,
    subido_por             uuid          NOT NULL REFERENCES usuario (id),
    confirmada_por         uuid          REFERENCES usuario (id),
    confirmada_en          timestamptz,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT orden_est_fk FOREIGN KEY (establecimiento_id, empresa_id) REFERENCES establecimiento (id, empresa_id),
    CONSTRAINT orden_archivo_fk FOREIGN KEY (archivo_id, empresa_id) REFERENCES archivo (id, empresa_id),
    -- El mismo PDF no se sube dos veces al mismo establecimiento.
    CONSTRAINT orden_sha256_ux UNIQUE (establecimiento_id, sha256)
);
CREATE INDEX orden_establecimiento_ix ON orden_servicio (establecimiento_id, created_at DESC);
CREATE INDEX orden_estado_ix ON orden_servicio (estado);

CREATE TABLE material_extraido (
    id                        uuid PRIMARY KEY,
    empresa_id                uuid          NOT NULL,
    orden_servicio_id         uuid          NOT NULL REFERENCES orden_servicio (id) ON DELETE CASCADE,
    posicion                  integer       NOT NULL,
    -- Fragmento literal del PDF (null solo en ítems agregados a mano).
    texto_original            varchar(2000),
    nombre_sugerido           varchar(200)  NOT NULL,
    categoria_sugerida        varchar(20)   NOT NULL CHECK (categoria_sugerida IN ('QUIMICO', 'PAPEL_Y_HIGIENE', 'BOLSAS', 'UTENSILIO', 'EPP', 'MAQUINARIA', 'OTRO')),
    unidad_sugerida           varchar(10)   NOT NULL CHECK (unidad_sugerida IN ('L', 'ML', 'KG', 'G', 'ROLLO', 'UNIDAD', 'PAQUETE', 'PAR', 'GALON')),
    presentacion              varchar(100),
    seccion_origen            varchar(20)   NOT NULL CHECK (seccion_origen IN ('MATERIALES', 'HERRAMIENTAS', 'MAQUINARIA', 'EQUIPAMIENTO', 'CLAUSULA_ENTIDAD', 'MANUAL')),
    abastece_sugerido         varchar(25)   NOT NULL CHECK (abastece_sugerido IN ('EMPRESA_LIMPIEZA', 'ENTIDAD_CONTRATANTE')),
    es_consumible_sugerido    boolean       NOT NULL,
    pagina                    integer,
    origen                    varchar(10)   NOT NULL CHECK (origen IN ('PARSER', 'IA', 'MANUAL')),
    confianza                 varchar(5)    NOT NULL CHECK (confianza IN ('ALTA', 'MEDIA', 'BAJA')),
    motivo_revision           varchar(500),
    seleccionado_por_defecto  boolean       NOT NULL,
    estado                    varchar(10)   NOT NULL CHECK (estado IN ('PENDIENTE', 'ACEPTADO', 'EDITADO', 'DESCARTADO')),
    -- Sugerencia de fusión (posible duplicado).
    fusionar_con_id           uuid          REFERENCES material_extraido (id),
    producto_id               uuid          REFERENCES producto (id),
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX material_orden_ix ON material_extraido (orden_servicio_id, posicion);

ALTER TABLE producto
    ADD CONSTRAINT producto_material_fk FOREIGN KEY (material_extraido_id) REFERENCES material_extraido (id);
