-- Las empresas se pueden desactivar desde la plataforma (su gente no puede entrar).
ALTER TABLE empresa ADD COLUMN activo boolean NOT NULL DEFAULT true;
