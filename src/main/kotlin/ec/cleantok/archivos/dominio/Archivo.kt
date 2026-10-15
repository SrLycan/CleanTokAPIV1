package ec.cleantok.archivos.dominio

import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

enum class TipoArchivo { PDF_ORDEN, FOTO }

@Entity
@Table(name = "archivo")
class Archivo(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val tipo: TipoArchivo,
    @Column(nullable = false, updatable = false) val ruta: String,
    @Column(nullable = false, updatable = false) val mime: String,
    @Column(nullable = false, updatable = false) val tamano: Long,
    @Column(nullable = false, updatable = false) val sha256: String,
    @Column(name = "nombre_original", updatable = false) val nombreOriginal: String?,
    @Column(name = "subido_por", nullable = false, updatable = false) val subidoPor: UUID,
) : EntidadBase()

interface ArchivoRepository : JpaRepository<Archivo, UUID> {
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Archivo?
}
