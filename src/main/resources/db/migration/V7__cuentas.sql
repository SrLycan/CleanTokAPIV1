-- Fase 8: gestión de cuentas. Las cuentas creadas por un admin nacen con una clave temporal que se cambia al entrar.
ALTER TABLE usuario ADD COLUMN debe_cambiar_clave boolean NOT NULL DEFAULT false;
