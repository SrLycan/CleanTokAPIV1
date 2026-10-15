-- Fase 7: proveedores, solicitudes consolidadas, ofertas, órdenes de compra, entregas y tracking.

-- ---- Proveedores y sus usuarios (PROVEEDOR y REPARTIDOR) ----

CREATE TABLE proveedor (
    id                    uuid PRIMARY KEY,
    nombre                varchar(200)  NOT NULL,
    ruc                   varchar(13)   NOT NULL UNIQUE,
    telefono              varchar(30),
    -- Local de venta: dirección para "retiro en local".
    direccion             varchar(500)  NOT NULL,
    lat                   numeric(9, 6) NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng                   numeric(9, 6) NOT NULL CHECK (lng BETWEEN -180 AND 180),
    -- Bodega centralizada (opcional): de aquí salen los envíos y aquí se puede retirar.
    bodega_direccion      varchar(500),
    bodega_lat            numeric(9, 6) CHECK (bodega_lat BETWEEN -90 AND 90),
    bodega_lng            numeric(9, 6) CHECK (bodega_lng BETWEEN -180 AND 180),
    envio_directo         boolean       NOT NULL DEFAULT true,
    retiro_local          boolean       NOT NULL DEFAULT false,
    retiro_bodega         boolean       NOT NULL DEFAULT false,
    activo                boolean       NOT NULL DEFAULT true,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT proveedor_bodega_ck CHECK ((bodega_direccion IS NULL) = (bodega_lat IS NULL) AND (bodega_lat IS NULL) = (bodega_lng IS NULL)),
    CONSTRAINT proveedor_retiro_bodega_ck CHECK (NOT retiro_bodega OR bodega_direccion IS NOT NULL),
    CONSTRAINT proveedor_modalidad_ck CHECK (envio_directo OR retiro_local OR retiro_bodega)
);

ALTER TABLE usuario ADD COLUMN proveedor_id uuid REFERENCES proveedor (id);
ALTER TABLE usuario DROP CONSTRAINT usuario_rol_check;
ALTER TABLE usuario DROP CONSTRAINT usuario_empresa_ck;
ALTER TABLE usuario ADD CONSTRAINT usuario_rol_check
    CHECK (rol IN ('SUPER_ADMIN', 'ADMIN_EMPRESA', 'ENCARGADO', 'PROVEEDOR', 'REPARTIDOR'));
-- Cada usuario pertenece a una empresa, a un proveedor o a nadie (solo el SUPER_ADMIN).
ALTER TABLE usuario ADD CONSTRAINT usuario_pertenencia_ck CHECK (
    (rol = 'SUPER_ADMIN' AND empresa_id IS NULL AND proveedor_id IS NULL) OR
    (rol IN ('ADMIN_EMPRESA', 'ENCARGADO') AND empresa_id IS NOT NULL AND proveedor_id IS NULL) OR
    (rol IN ('PROVEEDOR', 'REPARTIDOR') AND empresa_id IS NULL AND proveedor_id IS NOT NULL)
);
CREATE INDEX usuario_proveedor_ix ON usuario (proveedor_id);
ALTER TABLE usuario ADD CONSTRAINT usuario_id_proveedor_ux UNIQUE (id, proveedor_id);

-- Un proveedor atiende a varias empresas y una empresa trabaja con varios proveedores.
CREATE TABLE empresa_proveedor (
    id            uuid PRIMARY KEY,
    empresa_id    uuid        NOT NULL REFERENCES empresa (id),
    proveedor_id  uuid        NOT NULL REFERENCES proveedor (id),
    preferido     boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT empresa_proveedor_ux UNIQUE (empresa_id, proveedor_id)
);
CREATE INDEX empresa_proveedor_prov_ix ON empresa_proveedor (proveedor_id);

-- ---- Solicitud consolidada ----

CREATE TABLE solicitud (
    id               uuid PRIMARY KEY,
    empresa_id       uuid         NOT NULL REFERENCES empresa (id),
    codigo           varchar(20)  NOT NULL,
    estado           varchar(15)  NOT NULL CHECK (estado IN ('BORRADOR', 'EN_COTIZACION', 'ADJUDICADA', 'CANCELADA')),
    fecha_requerida  date         NOT NULL,
    nota             varchar(500),
    creada_por       uuid         NOT NULL REFERENCES usuario (id),
    enviada_en       timestamptz,
    adjudicada_en    timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT solicitud_codigo_ux UNIQUE (empresa_id, codigo),
    CONSTRAINT solicitud_id_empresa_ux UNIQUE (id, empresa_id)
);

-- Producto consolidado (mismo nombre normalizado y unidad en todos los sitios).
CREATE TABLE solicitud_item (
    id                  uuid PRIMARY KEY,
    solicitud_id        uuid           NOT NULL REFERENCES solicitud (id) ON DELETE CASCADE,
    nombre              varchar(200)   NOT NULL,
    nombre_normalizado  varchar(200)   NOT NULL,
    unidad              varchar(10)    NOT NULL,
    cantidad            numeric(14, 3) NOT NULL CHECK (cantidad > 0),
    created_at          timestamptz    NOT NULL DEFAULT now(),
    updated_at          timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT solicitud_item_ux UNIQUE (solicitud_id, nombre_normalizado, unidad)
);

-- Necesidad de un destino: el producto de ese establecimiento y su cantidad.
CREATE TABLE solicitud_linea (
    id                    uuid PRIMARY KEY,
    empresa_id            uuid           NOT NULL,
    solicitud_id          uuid           NOT NULL,
    item_id               uuid           NOT NULL REFERENCES solicitud_item (id) ON DELETE CASCADE,
    establecimiento_id    uuid           NOT NULL,
    producto_id           uuid           NOT NULL,
    cantidad              numeric(14, 3) NOT NULL CHECK (cantidad > 0),
    cantidad_recomendada  numeric(14, 3) CHECK (cantidad_recomendada >= 0),
    created_at            timestamptz    NOT NULL DEFAULT now(),
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT solicitud_linea_sol_fk FOREIGN KEY (solicitud_id, empresa_id) REFERENCES solicitud (id, empresa_id) ON DELETE CASCADE,
    CONSTRAINT solicitud_linea_prod_fk FOREIGN KEY (producto_id, establecimiento_id) REFERENCES producto (id, establecimiento_id),
    CONSTRAINT solicitud_linea_est_fk FOREIGN KEY (establecimiento_id, empresa_id) REFERENCES establecimiento (id, empresa_id),
    CONSTRAINT solicitud_linea_ux UNIQUE (solicitud_id, producto_id)
);
CREATE INDEX solicitud_linea_item_ix ON solicitud_linea (item_id);

-- Proveedores invitados a cotizar (los vinculados a la empresa al enviar la solicitud).
CREATE TABLE solicitud_proveedor (
    id            uuid PRIMARY KEY,
    solicitud_id  uuid        NOT NULL REFERENCES solicitud (id) ON DELETE CASCADE,
    proveedor_id  uuid        NOT NULL REFERENCES proveedor (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT solicitud_proveedor_ux UNIQUE (solicitud_id, proveedor_id)
);
CREATE INDEX solicitud_proveedor_prov_ix ON solicitud_proveedor (proveedor_id);

-- ---- Ofertas ----

CREATE TABLE oferta (
    id            uuid PRIMARY KEY,
    solicitud_id  uuid         NOT NULL REFERENCES solicitud (id) ON DELETE CASCADE,
    proveedor_id  uuid         NOT NULL REFERENCES proveedor (id),
    modalidad     varchar(20)  NOT NULL CHECK (modalidad IN ('ENVIO_DIRECTO', 'RETIRO_LOCAL', 'RETIRO_BODEGA')),
    plazo_dias    integer      NOT NULL CHECK (plazo_dias BETWEEN 0 AND 60),
    nota          varchar(500),
    estado        varchar(15)  NOT NULL CHECK (estado IN ('ENVIADA', 'ADJUDICADA', 'NO_ADJUDICADA')),
    usuario_id    uuid         NOT NULL REFERENCES usuario (id),
    enviada_en    timestamptz  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT oferta_ux UNIQUE (solicitud_id, proveedor_id)
);

CREATE TABLE oferta_item (
    id                   uuid PRIMARY KEY,
    oferta_id            uuid           NOT NULL REFERENCES oferta (id) ON DELETE CASCADE,
    item_id              uuid           NOT NULL REFERENCES solicitud_item (id) ON DELETE CASCADE,
    cantidad_disponible  numeric(14, 3) NOT NULL CHECK (cantidad_disponible > 0),
    precio_unitario      numeric(12, 4) NOT NULL CHECK (precio_unitario > 0),
    created_at           timestamptz    NOT NULL DEFAULT now(),
    updated_at           timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT oferta_item_ux UNIQUE (oferta_id, item_id)
);

-- ---- Órdenes de compra, entregas por destino y tracking ----

CREATE TABLE orden_compra (
    id             uuid PRIMARY KEY,
    empresa_id     uuid           NOT NULL REFERENCES empresa (id),
    proveedor_id   uuid           NOT NULL REFERENCES proveedor (id),
    solicitud_id   uuid           NOT NULL REFERENCES solicitud (id),
    oferta_id      uuid           NOT NULL REFERENCES oferta (id),
    codigo         varchar(20)    NOT NULL,
    modalidad      varchar(20)    NOT NULL CHECK (modalidad IN ('ENVIO_DIRECTO', 'RETIRO_LOCAL', 'RETIRO_BODEGA')),
    estado         varchar(20)    NOT NULL CHECK (estado IN (
        'APROBADA', 'ACEPTADA', 'RECHAZADA', 'EN_PREPARACION', 'EMPACADA',
        'LISTA_PARA_RETIRO', 'RETIRADA', 'DESPACHADA', 'EN_TRANSITO', 'ENTREGADA')),
    total          numeric(14, 2) NOT NULL CHECK (total >= 0),
    plazo_dias     integer        NOT NULL,
    repartidor_id  uuid,
    aprobada_por   uuid           NOT NULL REFERENCES usuario (id),
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT orden_compra_codigo_ux UNIQUE (empresa_id, codigo),
    CONSTRAINT orden_compra_id_empresa_ux UNIQUE (id, empresa_id),
    -- El repartidor debe ser del mismo proveedor.
    CONSTRAINT orden_compra_repartidor_fk FOREIGN KEY (repartidor_id, proveedor_id) REFERENCES usuario (id, proveedor_id)
);
CREATE INDEX orden_compra_proveedor_ix ON orden_compra (proveedor_id, estado);
CREATE INDEX orden_compra_repartidor_ix ON orden_compra (repartidor_id);

CREATE TABLE entrega (
    id                  uuid PRIMARY KEY,
    empresa_id          uuid        NOT NULL,
    orden_id            uuid        NOT NULL,
    establecimiento_id  uuid        NOT NULL,
    parada              integer     NOT NULL,
    estado              varchar(12) NOT NULL CHECK (estado IN ('PENDIENTE', 'ENTREGADA', 'RECIBIDA')),
    entregada_en        timestamptz,
    recibida_en         timestamptz,
    recibida_por        uuid REFERENCES usuario (id),
    completa            boolean,
    nota                varchar(500),
    foto_id             uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT entrega_orden_fk FOREIGN KEY (orden_id, empresa_id) REFERENCES orden_compra (id, empresa_id),
    CONSTRAINT entrega_est_fk FOREIGN KEY (establecimiento_id, empresa_id) REFERENCES establecimiento (id, empresa_id),
    CONSTRAINT entrega_foto_fk FOREIGN KEY (foto_id, empresa_id) REFERENCES archivo (id, empresa_id),
    CONSTRAINT entrega_ux UNIQUE (orden_id, establecimiento_id),
    CONSTRAINT entrega_recibida_ck CHECK ((estado = 'RECIBIDA') = (recibida_en IS NOT NULL))
);
CREATE INDEX entrega_est_ix ON entrega (establecimiento_id, estado);

CREATE TABLE orden_linea (
    id                  uuid PRIMARY KEY,
    orden_id            uuid           NOT NULL REFERENCES orden_compra (id),
    entrega_id          uuid           NOT NULL REFERENCES entrega (id),
    solicitud_linea_id  uuid           NOT NULL REFERENCES solicitud_linea (id),
    producto_id         uuid           NOT NULL REFERENCES producto (id),
    cantidad            numeric(14, 3) NOT NULL CHECK (cantidad > 0),
    unidad              varchar(10)    NOT NULL,
    precio_unitario     numeric(12, 4) NOT NULL CHECK (precio_unitario > 0),
    cantidad_recibida   numeric(14, 3) CHECK (cantidad_recibida >= 0),
    movimiento_id       uuid REFERENCES movimiento (id),
    created_at          timestamptz    NOT NULL DEFAULT now(),
    updated_at          timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT orden_linea_ux UNIQUE (orden_id, solicitud_linea_id)
);
CREATE INDEX orden_linea_entrega_ix ON orden_linea (entrega_id);

-- Línea de tiempo del pedido (como el rastreo de un courier). Inalterable.
CREATE TABLE evento_orden (
    id           uuid PRIMARY KEY,
    secuencia    bigserial    NOT NULL UNIQUE,
    orden_id     uuid         NOT NULL REFERENCES orden_compra (id),
    entrega_id   uuid REFERENCES entrega (id),
    estado       varchar(20)  NOT NULL,
    titulo       varchar(200) NOT NULL,
    detalle      varchar(500),
    usuario_id   uuid         NOT NULL REFERENCES usuario (id),
    ocurrido_en  timestamptz  NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX evento_orden_ix ON evento_orden (orden_id, secuencia);

CREATE FUNCTION evento_orden_inmutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Los eventos del pedido son inmutables';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER evento_orden_inmutable_tg
    BEFORE UPDATE OR DELETE ON evento_orden
    FOR EACH ROW EXECUTE FUNCTION evento_orden_inmutable();
