package ec.cleantok.establecimiento.dominio

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/**
 * Las consultas por empresa e id excluyen los eliminados (que además quedan inactivos, así que las de
 * "activos" ya los excluyen). `findById` / `findAllById` sí los traen: el historial de pedidos muestra su nombre.
 */
interface EstablecimientoRepository : JpaRepository<Establecimiento, UUID> {
    @Query("select e from Establecimiento e where e.id = :id and e.empresaId = :empresaId and e.eliminadoEn is null")
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Establecimiento?

    @Query("select e from Establecimiento e where e.empresaId = :empresaId and e.eliminadoEn is null")
    fun findAllByEmpresaId(empresaId: UUID, pageable: Pageable): Page<Establecimiento>

    @Query("select e from Establecimiento e where e.empresaId = :empresaId and e.activo = :activo and e.eliminadoEn is null")
    fun findAllByEmpresaIdAndActivo(empresaId: UUID, activo: Boolean, pageable: Pageable): Page<Establecimiento>

    /** Entregas que todavía no se reciben en el establecimiento: mientras haya alguna no se puede eliminar. */
    @Query("select count(*) from entrega where establecimiento_id = :id and estado <> 'RECIBIDA'", nativeQuery = true)
    fun entregasEnCurso(id: UUID): Long

    fun findAllByEmpresaIdAndActivoTrueOrderByNombre(empresaId: UUID): List<Establecimiento>

    @Query(
        """
        select e from Establecimiento e, UsuarioEstablecimiento ue
        where ue.establecimientoId = e.id and ue.usuarioId = :usuarioId and e.activo = true
        order by e.nombre
        """,
    )
    fun asignadosActivos(usuarioId: UUID): List<Establecimiento>
}

interface UsuarioEstablecimientoRepository : JpaRepository<UsuarioEstablecimiento, UUID> {
    fun existsByUsuarioIdAndEstablecimientoId(usuarioId: UUID, establecimientoId: UUID): Boolean

    fun findAllByEstablecimientoId(establecimientoId: UUID): List<UsuarioEstablecimiento>

    fun findAllByUsuarioId(usuarioId: UUID): List<UsuarioEstablecimiento>

    @Modifying
    @Query("delete from UsuarioEstablecimiento ue where ue.establecimientoId = :establecimientoId and ue.usuarioId = :usuarioId")
    fun quitar(establecimientoId: UUID, usuarioId: UUID): Int
}

interface UbicacionRepository : JpaRepository<Ubicacion, UUID> {
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Ubicacion?

    fun findByQrToken(qrToken: String): Ubicacion?

    fun findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(establecimientoId: UUID): List<Ubicacion>

    fun findByEstablecimientoIdAndTipo(establecimientoId: UUID, tipo: TipoUbicacion): Ubicacion?

    @Query("select coalesce(max(u.orden), 0) from Ubicacion u where u.establecimientoId = :establecimientoId")
    fun maxOrden(establecimientoId: UUID): Int
}
