-- Quién creó cada establecimiento (solo él o el administrador de la plataforma pueden eliminarlo)
-- y eliminación lógica: el establecimiento desaparece de la app y sus QR dejan de funcionar,
-- pero sus movimientos, pedidos y entregas se conservan para la trazabilidad.
ALTER TABLE establecimiento ADD COLUMN creado_por uuid REFERENCES usuario (id);
ALTER TABLE establecimiento ADD COLUMN eliminado_en timestamptz;

-- Los que ya existían se atribuyen al primer administrador de su empresa.
UPDATE establecimiento e SET creado_por = (
    SELECT u.id FROM usuario u
    WHERE u.empresa_id = e.empresa_id AND u.rol = 'ADMIN_EMPRESA'
    ORDER BY u.created_at, u.id
    LIMIT 1
);
