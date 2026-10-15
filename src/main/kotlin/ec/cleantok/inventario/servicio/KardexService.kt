package ec.cleantok.inventario.servicio

import ec.cleantok.catalogo.servicio.ProductoService
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.inventario.api.KardexItemDto
import ec.cleantok.inventario.dominio.Movimiento
import ec.cleantok.inventario.dominio.MovimientoRepository
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Service
class KardexService(
    private val productos: ProductoService,
    private val movimientos: MovimientoRepository,
    private val acceso: AccesoEstablecimiento,
    private val mapper: MovimientoMapper,
) {
    /**
     * Kardex de un producto en orden de registro (el orden en que se aplicaron los saldos).
     * Con [ubicacionId] muestra entrada, salida y saldo de esa ubicación.
     */
    @Transactional(readOnly = true)
    fun kardex(productoId: UUID, desde: OffsetDateTime?, hasta: OffsetDateTime?, ubicacionId: UUID?, pageable: Pageable): Page<KardexItemDto> {
        val p = productos.deMiEmpresa(productoId)
        acceso.visible(p.establecimientoId)
        ubicacionId?.let {
            val u = acceso.ubicacionDeMiEmpresa(it)
            if (u.establecimientoId != p.establecimientoId) throw ValidacionException("La ubicación no es del establecimiento del producto")
        }
        if (desde != null && hasta != null && desde.isAfter(hasta)) throw ValidacionException("'desde' no puede ser posterior a 'hasta'")

        val spec = Specification<Movimiento> { root, _, cb ->
            val f = mutableListOf<Predicate>(cb.equal(root.get<UUID>("productoId"), p.id))
            desde?.let { f += cb.greaterThanOrEqualTo(root.get("ocurridoEn"), it.toInstant()) }
            hasta?.let { f += cb.lessThanOrEqualTo(root.get<Instant>("ocurridoEn"), it.toInstant()) }
            ubicacionId?.let {
                f += cb.or(cb.equal(root.get<UUID>("ubicacionOrigenId"), it), cb.equal(root.get<UUID>("ubicacionDestinoId"), it))
            }
            cb.and(*f.toTypedArray())
        }
        val pagina = movimientos.findAll(spec, pageable)
        val dtos = mapper.dtos(pagina.content)
        val items = pagina.content.zip(dtos).map { (m, dto) ->
            when (ubicacionId) {
                null -> KardexItemDto(dto, null, null, null)
                m.ubicacionDestinoId -> KardexItemDto(dto, m.cantidad, null, m.saldoResultanteDestino)
                else -> KardexItemDto(dto, null, m.cantidad, m.saldoResultanteOrigen)
            }
        }
        return PageImpl(items, pagina.pageable, pagina.totalElements)
    }
}
