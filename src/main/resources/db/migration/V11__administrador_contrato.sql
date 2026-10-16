-- Administrador de contrato: usuario de la empresa que monitorea (solo lectura) el consumo por piso de las sedes
-- que tiene asignadas (usuario_establecimiento): consumos atípicos y suficiencia de insumos.
ALTER TABLE usuario DROP CONSTRAINT usuario_rol_check;
ALTER TABLE usuario ADD CONSTRAINT usuario_rol_check
    CHECK (rol IN ('SUPER_ADMIN', 'ADMIN_EMPRESA', 'SUPERVISOR', 'ADMIN_CONTRATO', 'ENCARGADO', 'PROVEEDOR', 'REPARTIDOR'));
ALTER TABLE usuario DROP CONSTRAINT usuario_pertenencia_ck;
ALTER TABLE usuario ADD CONSTRAINT usuario_pertenencia_ck CHECK (
    (rol = 'SUPER_ADMIN' AND empresa_id IS NULL AND proveedor_id IS NULL) OR
    (rol IN ('ADMIN_EMPRESA', 'SUPERVISOR', 'ADMIN_CONTRATO', 'ENCARGADO') AND empresa_id IS NOT NULL AND proveedor_id IS NULL) OR
    (rol IN ('PROVEEDOR', 'REPARTIDOR') AND empresa_id IS NULL AND proveedor_id IS NOT NULL)
);
