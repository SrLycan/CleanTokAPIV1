-- Fase 3: catálogo de productos, saldos (stock) y movimientos inmutables (kardex).

ALTER TABLE ubicacion ADD CONSTRAINT ubicacion_id_est_ux UNIQUE (id, establecimiento_id);

CREATE TABLE producto (
    id                   uuid PRIMARY KEY,
    empresa_id           uuid           NOT NULL,
    establecimiento_id   uuid           NOT NULL,
    nombre               varchar(200)   NOT NULL,
    nombre_normalizado   varchar(200)   NOT NULL,
    categoria            varchar(20)    NOT NULL CHECK (categoria IN ('QUIMICO', 'PAPEL_Y_HIGIENE', 'BOLSAS', 'UTENSILIO', 'EPP', 'MAQUINARIA', 'OTRO')),
    es_consumible        boolean        NOT NULL,
    unidad               varchar(10)    NOT NULL CHECK (unidad IN ('L', 'ML', 'KG', 'G', 'ROLLO', 'UNIDAD', 'PAQUETE', 'PAR', 'GALON')),
    presentacion         varchar(100),
    abastece             varchar(25)    NOT NULL CHECK (abastece IN ('EMPRESA_LIMPIEZA', 'ENTIDAD_CONTRATANTE')),
    stock_minimo         numeric(14, 3) CHECK (stock_minimo >= 0),
    origen               varchar(10)    NOT NULL CHECK (origen IN ('PDF', 'MANUAL')),
    material_extraido_id uuid,
    activo               boolean        NOT NULL DEFAULT true,
    created_at           timestamptz    NOT NULL DEFAULT now(),
    updated_at           timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT producto_est_fk FOREIGN KEY (establecimiento_id, empresa_id) REFERENCES establecimiento (id, empresa_id),
    CONSTRAINT producto_id_est_ux UNIQUE (id, establecimiento_id),
    -- Nombre único por establecimiento sin distinguir mayúsculas ni tildes (normalizado en la aplicación).
    CONSTRAINT producto_nombre_ux UNIQUE (establecimiento_id, nombre_normalizado)
);

-- Saldo por producto y ubicación. Derivado de los movimientos; nunca negativo.
CREATE TABLE stock (
    id                  uuid PRIMARY KEY,
    empresa_id          uuid           NOT NULL,
    establecimiento_id  uuid           NOT NULL,
    producto_id         uuid           NOT NULL,
    ubicacion_id        uuid           NOT NULL,
    cantidad            numeric(14, 3) NOT NULL DEFAULT 0 CHECK (cantidad >= 0),
    version             bigint         NOT NULL DEFAULT 0,
    created_at          timestamptz    NOT NULL DEFAULT now(),
    updated_at          timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT stock_ux UNIQUE (producto_id, ubicacion_id),
    -- Producto y ubicación deben ser del mismo establecimiento.
    CONSTRAINT stock_producto_fk FOREIGN KEY (producto_id, establecimiento_id) REFERENCES producto (id, establecimiento_id),
    CONSTRAINT stock_ubicacion_fk FOREIGN KEY (ubicacion_id, establecimiento_id) REFERENCES ubicacion (id, establecimiento_id)
);
CREATE INDEX stock_establecimiento_ix ON stock (establecimiento_id);

CREATE TABLE movimiento (
    id                        uuid PRIMARY KEY,
    secuencia                 bigserial      NOT NULL UNIQUE,
    empresa_id                uuid           NOT NULL,
    establecimiento_id        uuid           NOT NULL,
    client_uuid               uuid           NOT NULL UNIQUE,
    tipo                      varchar(12)    NOT NULL CHECK (tipo IN ('INGRESO', 'TRASLADO', 'CONSUMO', 'DEVOLUCION', 'AJUSTE')),
    producto_id               uuid           NOT NULL,
    ubicacion_origen_id       uuid,
    ubicacion_destino_id      uuid,
    cantidad                  numeric(14, 3) NOT NULL CHECK (cantidad > 0),
    unidad                    varchar(10)    NOT NULL,
    cantidad_original         numeric(14, 3) NOT NULL CHECK (cantidad_original > 0),
    unidad_original           varchar(10)    NOT NULL,
    usuario_id                uuid           NOT NULL REFERENCES usuario (id),
    ocurrido_en               timestamptz    NOT NULL,
    registrado_en             timestamptz    NOT NULL,
    nota                      varchar(500),
    motivo                    varchar(500),
    foto_id                   uuid,
    saldo_resultante_origen   numeric(14, 3),
    saldo_resultante_destino  numeric(14, 3),
    created_at                timestamptz    NOT NULL DEFAULT now(),
    updated_at                timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT movimiento_producto_fk FOREIGN KEY (producto_id, establecimiento_id) REFERENCES producto (id, establecimiento_id),
    CONSTRAINT movimiento_origen_fk FOREIGN KEY (ubicacion_origen_id, establecimiento_id) REFERENCES ubicacion (id, establecimiento_id),
    CONSTRAINT movimiento_destino_fk FOREIGN KEY (ubicacion_destino_id, establecimiento_id) REFERENCES ubicacion (id, establecimiento_id),
    CONSTRAINT movimiento_motivo_ck CHECK (tipo <> 'AJUSTE' OR (motivo IS NOT NULL AND length(trim(motivo)) > 0)),
    -- Forma de cada tipo: qué ubicaciones lleva.
    CONSTRAINT movimiento_forma_ck CHECK (
        (tipo = 'INGRESO'    AND ubicacion_origen_id IS NULL     AND ubicacion_destino_id IS NOT NULL) OR
        (tipo = 'TRASLADO'   AND ubicacion_origen_id IS NOT NULL AND ubicacion_destino_id IS NOT NULL) OR
        (tipo = 'CONSUMO'    AND ubicacion_origen_id IS NOT NULL AND ubicacion_destino_id IS NULL) OR
        (tipo = 'DEVOLUCION' AND ubicacion_origen_id IS NOT NULL AND ubicacion_destino_id IS NOT NULL) OR
        (tipo = 'AJUSTE'     AND (ubicacion_origen_id IS NULL) <> (ubicacion_destino_id IS NULL))
    ),
    CONSTRAINT movimiento_saldos_ck CHECK (
        (ubicacion_origen_id IS NULL) = (saldo_resultante_origen IS NULL) AND
        (ubicacion_destino_id IS NULL) = (saldo_resultante_destino IS NULL)
    )
);
CREATE INDEX movimiento_producto_ix ON movimiento (producto_id, secuencia);
CREATE INDEX movimiento_consumo_ix ON movimiento (establecimiento_id, tipo, ocurrido_en);

-- Kardex inalterable: ni UPDATE ni DELETE (las correcciones se hacen con AJUSTE).
CREATE FUNCTION movimiento_inmutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Los movimientos son inmutables: registra un AJUSTE para corregir';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER movimiento_inmutable_tg
    BEFORE UPDATE OR DELETE ON movimiento
    FOR EACH ROW EXECUTE FUNCTION movimiento_inmutable();
