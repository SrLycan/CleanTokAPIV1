package ec.cleantok.catalogo.dominio

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface ProductoRepository : JpaRepository<Producto, UUID>, JpaSpecificationExecutor<Producto> {
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Producto?

    fun existsByEstablecimientoIdAndNombreNormalizado(establecimientoId: UUID, nombreNormalizado: String): Boolean

    fun findByEstablecimientoIdAndNombreNormalizado(establecimientoId: UUID, nombreNormalizado: String): Producto?

    fun findAllByEstablecimientoIdAndActivoTrueOrderByNombre(establecimientoId: UUID): List<Producto>

    fun findAllByEstablecimientoIdOrderByNombre(establecimientoId: UUID): List<Producto>
}
