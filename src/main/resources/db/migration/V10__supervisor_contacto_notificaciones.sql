-- Supervisor de establecimiento: usuario de la empresa que controla solo las sedes que tiene asignadas
-- (usuario_establecimiento, igual que el encargado).
ALTER TABLE usuario DROP CONSTRAINT usuario_rol_check;
ALTER TABLE usuario ADD CONSTRAINT usuario_rol_check
    CHECK (rol IN ('SUPER_ADMIN', 'ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO', 'PROVEEDOR', 'REPARTIDOR'));
ALTER TABLE usuario DROP CONSTRAINT usuario_pertenencia_ck;
ALTER TABLE usuario ADD CONSTRAINT usuario_pertenencia_ck CHECK (
    (rol = 'SUPER_ADMIN' AND empresa_id IS NULL AND proveedor_id IS NULL) OR
    (rol IN ('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO') AND empresa_id IS NOT NULL AND proveedor_id IS NULL) OR
    (rol IN ('PROVEEDOR', 'REPARTIDOR') AND empresa_id IS NULL AND proveedor_id IS NOT NULL)
);

-- Contacto de la empresa: lo ve el proveedor que cotiza (razón social y RUC ya existían).
ALTER TABLE empresa ADD COLUMN telefono varchar(30);
ALTER TABLE empresa ADD COLUMN email varchar(254);
ALTER TABLE empresa ADD COLUMN direccion varchar(500);
ALTER TABLE empresa ADD COLUMN lat numeric(9, 6);
ALTER TABLE empresa ADD COLUMN lng numeric(9, 6);

-- Notificaciones dentro de la app (p. ej. "un proveedor envió su cotización").
CREATE TABLE notificacion (
    id               uuid PRIMARY KEY,
    usuario_id       uuid         NOT NULL REFERENCES usuario (id),
    tipo             varchar(40)  NOT NULL,
    titulo           varchar(200) NOT NULL,
    mensaje          varchar(500) NOT NULL,
    referencia_tipo  varchar(40),
    referencia_id    uuid,
    referencia_texto varchar(60),
    leida_en         timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX notificacion_usuario_ix ON notificacion (usuario_id, created_at DESC);
