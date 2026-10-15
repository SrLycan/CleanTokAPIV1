package ec.cleantok.inventario.servicio

import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.establecimiento.api.ProductoEnUbicacionDto
import ec.cleantok.establecimiento.api.QrResolucionDto
import ec.cleantok.establecimiento.servicio.ContenidoQr
import ec.cleantok.inventario.dominio.StockRepository
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal

/** Agrega a la respuesta del QR los productos activos con su stock en esa ubicación y en la bodega. */
@Component
@Order(1)
class ContenidoQrInventario(
    private val productos: ProductoRepository,
    private val stocks: StockRepository,
) : ContenidoQr {
    override fun completar(base: QrResolucionDto): QrResolucionDto {
        val lista = productos.findAllByEstablecimientoIdAndActivoTrueOrderByNombre(base.establecimiento.id)
        val enUbicacion = stocks.findAllByUbicacionId(base.ubicacion.id).associate { it.productoId to it.cantidad }
        val enBodega = if (base.bodega.id == base.ubicacion.id) enUbicacion
        else stocks.findAllByUbicacionId(base.bodega.id).associate { it.productoId to it.cantidad }
        return base.copy(
            productos = lista.map { p ->
                ProductoEnUbicacionDto(
                    id = p.id,
                    nombre = p.nombre,
                    categoria = p.categoria,
                    esConsumible = p.esConsumible,
                    unidad = p.unidad,
                    unidadesCompatibles = p.unidad.compatibles(),
                    presentacion = p.presentacion,
                    abastece = p.abastece,
                    stockUbicacion = enUbicacion[p.id] ?: CERO,
                    stockBodega = enBodega[p.id] ?: CERO,
                )
            },
        )
    }

    companion object {
        private val CERO: BigDecimal = BigDecimal.ZERO.setScale(3)
    }
}
