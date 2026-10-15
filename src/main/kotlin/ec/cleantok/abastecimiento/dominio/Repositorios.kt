package ec.cleantok.abastecimiento.dominio

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface EmpresaProveedorRepository : JpaRepository<EmpresaProveedor, UUID> {
    fun findAllByEmpresaId(empresaId: UUID): List<EmpresaProveedor>

    fun findByEmpresaIdAndProveedorId(empresaId: UUID, proveedorId: UUID): EmpresaProveedor?
}

interface SolicitudRepository : JpaRepository<Solicitud, UUID> {
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Solicitud?

    fun findAllByEmpresaIdOrderByCreatedAtDesc(empresaId: UUID): List<Solicitud>

    fun countByEmpresaId(empresaId: UUID): Long

    @Query(
        """
        select s from Solicitud s, SolicitudProveedor sp
        where sp.solicitudId = s.id and sp.proveedorId = :proveedorId and s.estado <> ec.cleantok.abastecimiento.dominio.EstadoSolicitud.BORRADOR
        order by s.enviadaEn desc
        """,
    )
    fun invitadas(proveedorId: UUID): List<Solicitud>
}

interface SolicitudItemRepository : JpaRepository<SolicitudItem, UUID> {
    fun findAllBySolicitudIdOrderByNombre(solicitudId: UUID): List<SolicitudItem>
}

interface SolicitudLineaRepository : JpaRepository<SolicitudLinea, UUID> {
    fun findAllBySolicitudId(solicitudId: UUID): List<SolicitudLinea>
}

interface SolicitudProveedorRepository : JpaRepository<SolicitudProveedor, UUID> {
    fun existsBySolicitudIdAndProveedorId(solicitudId: UUID, proveedorId: UUID): Boolean

    fun findAllBySolicitudId(solicitudId: UUID): List<SolicitudProveedor>
}

interface OfertaRepository : JpaRepository<Oferta, UUID> {
    fun findAllBySolicitudId(solicitudId: UUID): List<Oferta>

    fun findBySolicitudIdAndProveedorId(solicitudId: UUID, proveedorId: UUID): Oferta?
}

interface OfertaItemRepository : JpaRepository<OfertaItem, UUID> {
    fun findAllByOfertaIdIn(ofertaIds: Collection<UUID>): List<OfertaItem>

    fun deleteAllByOfertaId(ofertaId: UUID)
}

interface OrdenCompraRepository : JpaRepository<OrdenCompra, UUID> {
    fun findAllByEmpresaIdOrderByCreatedAtDesc(empresaId: UUID): List<OrdenCompra>

    fun findAllByProveedorIdOrderByCreatedAtDesc(proveedorId: UUID): List<OrdenCompra>

    fun findAllByRepartidorIdOrderByCreatedAtDesc(repartidorId: UUID): List<OrdenCompra>

    fun findAllBySolicitudId(solicitudId: UUID): List<OrdenCompra>

    fun countByEmpresaId(empresaId: UUID): Long

    @Query(
        """
        select distinct o from OrdenCompra o, Entrega e, ec.cleantok.establecimiento.dominio.UsuarioEstablecimiento ue
        where e.ordenId = o.id and ue.establecimientoId = e.establecimientoId and ue.usuarioId = :usuarioId
        order by o.createdAt desc
        """,
    )
    fun conEntregasPara(usuarioId: UUID): List<OrdenCompra>
}

interface EntregaRepository : JpaRepository<Entrega, UUID> {
    fun findAllByOrdenIdOrderByParada(ordenId: UUID): List<Entrega>

    fun findAllByOrdenIdIn(ordenIds: Collection<UUID>): List<Entrega>
}

interface OrdenLineaRepository : JpaRepository<OrdenLinea, UUID> {
    fun findAllByOrdenId(ordenId: UUID): List<OrdenLinea>

    fun findAllByEntregaId(entregaId: UUID): List<OrdenLinea>
}

interface EventoOrdenRepository : JpaRepository<EventoOrden, UUID> {
    fun findAllByOrdenIdOrderBySecuencia(ordenId: UUID): List<EventoOrden>
}
