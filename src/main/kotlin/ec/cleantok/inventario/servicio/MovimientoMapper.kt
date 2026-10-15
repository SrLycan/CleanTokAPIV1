package ec.cleantok.inventario.servicio

import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.api.MovimientoDto
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.api.UbicacionRefDto
import ec.cleantok.inventario.dominio.Movimiento
import org.springframework.stereotype.Component

/** Convierte movimientos a DTO cargando nombres en lote (una consulta por tipo de entidad). */
@Component
class MovimientoMapper(
    private val productos: ProductoRepository,
    private val ubicaciones: UbicacionRepository,
    private val usuarios: UsuarioRepository,
) {
    fun dtos(lista: List<Movimiento>): List<MovimientoDto> {
        if (lista.isEmpty()) return emptyList()
        val prods = productos.findAllById(lista.map { it.productoId }.toSet()).associateBy { it.id }
        val ubics = ubicaciones.findAllById(lista.flatMap { listOfNotNull(it.ubicacionOrigenId, it.ubicacionDestinoId) }.toSet())
            .associate { it.id to UbicacionRefDto(it.id, it.nombre, it.tipo) }
        val usrs = usuarios.findAllById(lista.map { it.usuarioId }.toSet()).associate { it.id to RefDto(it.id, it.nombre) }
        return lista.map { m ->
            val p = prods.getValue(m.productoId)
            MovimientoDto(
                id = m.id,
                clientUuid = m.clientUuid,
                tipo = m.tipo,
                producto = ProductoRefDto(p.id, p.nombre, p.unidad),
                origen = m.ubicacionOrigenId?.let(ubics::getValue),
                destino = m.ubicacionDestinoId?.let(ubics::getValue),
                cantidad = m.cantidad,
                unidad = m.unidad,
                cantidadOriginal = m.cantidadOriginal,
                unidadOriginal = m.unidadOriginal,
                ocurridoEn = Zona.presentar(m.ocurridoEn),
                registradoEn = Zona.presentar(m.registradoEn),
                usuario = usrs.getValue(m.usuarioId),
                nota = m.nota,
                motivo = m.motivo,
                fotoId = m.fotoId,
                saldoResultanteOrigen = m.saldoResultanteOrigen,
                saldoResultanteDestino = m.saldoResultanteDestino,
            )
        }
    }

    fun dto(m: Movimiento): MovimientoDto = dtos(listOf(m)).single()
}
