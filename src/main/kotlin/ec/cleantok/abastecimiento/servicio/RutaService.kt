package ec.cleantok.abastecimiento.servicio

import ec.cleantok.abastecimiento.api.ParadaDto
import ec.cleantok.abastecimiento.api.PuntoDto
import ec.cleantok.abastecimiento.api.ResumenRutaDto
import ec.cleantok.abastecimiento.api.RutaDto
import ec.cleantok.abastecimiento.dominio.Entrega
import ec.cleantok.abastecimiento.dominio.EntregaRepository
import ec.cleantok.abastecimiento.dominio.EstadoEntrega
import ec.cleantok.abastecimiento.dominio.EstadoOrden
import ec.cleantok.abastecimiento.dominio.OrdenCompra
import ec.cleantok.abastecimiento.dominio.OrdenCompraRepository
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.identidad.dominio.Proveedor
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.inventario.api.RefDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Rutas de reparto (RF-15/16). Sale de la bodega del proveedor (o de su local si no tiene bodega) y visita
 * cada destino. "Base" es el orden en que se armó el pedido; "optimizada", vecino más cercano + 2-opt sobre las
 * distancias por calles (OSRM), o en línea recta × factor si el enrutador no responde.
 */
@Service
class RutaService(
    private val ordenes: OrdenCompraRepository,
    private val entregas: EntregaRepository,
    private val establecimientos: EstablecimientoRepository,
    private val proveedores: ProveedorRepository,
    private val matrizVial: MatrizVial,
    private val props: CleanTokProperties,
) {
    /** Ruta de una orden de envío directo (la orden ya fue validada como visible). */
    @Transactional(readOnly = true)
    fun deOrden(o: OrdenCompra): RutaDto {
        if (!o.esEnvio) throw ValidacionException("La orden es para retiro: no tiene ruta de reparto")
        return calcular(proveedores.findById(o.proveedorId).orElseThrow { NoEncontradoException("Proveedor") }, mapOf(o to entregas.findAllByOrdenIdOrderByParada(o.id)))
    }

    /** Ruta del día del repartidor: todas sus órdenes empacadas o en camino, con los destinos repetidos unidos. */
    @Transactional(readOnly = true)
    fun delRepartidor(): RutaDto {
        val u = ContextoSeguridad.usuario()
        val activas = ordenes.findAllByRepartidorIdOrderByCreatedAtDesc(u.id).filter { it.estado in ACTIVAS }
        val proveedor = proveedores.findById(ContextoSeguridad.proveedorId()).orElseThrow { NoEncontradoException("Proveedor") }
        val porOrden = entregas.findAllByOrdenIdIn(activas.map { it.id }).groupBy { it.ordenId }
        return calcular(proveedor, activas.associateWith { porOrden[it.id].orEmpty() })
    }

    /** Parada optimizada (1, 2, 3…) por entrega, para guardarla al despachar. */
    fun ordenOptimizado(o: OrdenCompra): Map<UUID, Int> {
        val ruta = deOrden(o)
        return ruta.paradas.flatMap { p -> p.entregas.map { it to p.orden } }.toMap()
    }

    private fun calcular(proveedor: Proveedor, porOrden: Map<OrdenCompra, List<Entrega>>): RutaDto {
        val origen = origen(proveedor)
        val todas = porOrden.values.flatten()
        val ests = establecimientos.findAllById(todas.map { it.establecimientoId }.toSet()).associateBy { it.id }
        val (conCoord, sinCoord) = todas.map { it.establecimientoId }.distinct().map { ests.getValue(it) }.partition { it.lat != null && it.lng != null }

        // Base: los destinos en el orden en que se armó el pedido (alfabético). No se usa `parada`, que al despachar
        // pasa a ser el orden optimizado: la comparación sería consigo misma.
        val destinos = conCoord.sortedBy { it.nombre }
        val puntos = destinos.map { Punto(it.lat!!.toDouble(), it.lng!!.toDouble()) }
        val origenPunto = Punto(origen.lat!!.toDouble(), origen.lng!!.toDouble())
        val a = props.abastecimiento
        // Por calles si OSRM responde; si no, línea recta × factor a velocidad promedio.
        val vial = matrizVial.calcular(listOf(origenPunto) + puntos)
        val km = vial?.km ?: OptimizadorRutas.matriz(origenPunto, puntos).map { fila -> fila.map { it * a.factorRuta }.toDoubleArray() }.toTypedArray()
        val minutos = vial?.minutos ?: km.map { fila -> fila.map { it / a.velocidadKmH * 60 }.toDoubleArray() }.toTypedArray()
        val base = puntos.indices.toList()
        val optima = OptimizadorRutas.optimizar(km)

        var anterior = 0
        val paradas = optima.mapIndexed { i, idx ->
            val e = destinos[idx]
            val suyas = todas.filter { it.establecimientoId == e.id }
            val tramo = km[anterior][idx + 1]
            anterior = idx + 1
            ParadaDto(
                orden = i + 1,
                establecimiento = RefDto(e.id, e.nombre),
                direccion = e.direccion,
                lat = e.lat!!,
                lng = e.lng!!,
                estado = suyas.minOf { it.estado },
                ordenes = porOrden.filterValues { es -> es.any { it.establecimientoId == e.id } }.keys.map { RefDto(it.id, it.codigo) },
                entregas = suyas.map { it.id },
                kmTramo = BigDecimal(tramo).setScale(1, RoundingMode.HALF_UP),
            )
        }
        val resumenBase = resumen(OptimizadorRutas.largo(km, base), OptimizadorRutas.largo(minutos, base), puntos.size)
        val resumenOpt = resumen(OptimizadorRutas.largo(km, optima), OptimizadorRutas.largo(minutos, optima), puntos.size)
        val ahorro = resumenBase.km.subtract(resumenOpt.km).max(BigDecimal.ZERO)
        // ordenBase expresado como índices dentro de `paradas` (que va en orden optimizado).
        val posicion = optima.withIndex().associate { (pos, idx) -> idx to pos }
        return RutaDto(
            origen = origen,
            paradas = paradas,
            ordenBase = base.map { posicion.getValue(it) },
            base = resumenBase,
            optimizada = resumenOpt,
            ahorroKm = ahorro,
            ahorroPct = if (resumenBase.km.signum() == 0) 0 else (ahorro.toDouble() / resumenBase.km.toDouble() * 100).roundToInt(),
            ahorroCo2Kg = resumenBase.co2Kg.subtract(resumenOpt.co2Kg).max(BigDecimal.ZERO),
            metodo = (
                if (vial != null) "Distancias y tiempos de manejo por calles (OSRM, OpenStreetMap)"
                else "Sin enrutador: distancia en línea recta × ${coma(a.factorRuta)} a ${a.velocidadKmH.roundToInt()} km/h"
                ) + ", ${a.minutosPorParada} min por parada; CO₂e = km × ${coma(a.co2KgPorKm)} kg/km.",
            fuente = if (vial != null) "OSRM" else "ESTIMADA",
            sinCoordenadas = sinCoord.map { RefDto(it.id, it.nombre) },
        )
    }

    private fun coma(v: Double) = v.toString().replace('.', ',')

    private fun origen(p: Proveedor): PuntoDto =
        if (p.bodegaLat != null) PuntoDto("Bodega de ${p.nombre}", p.bodegaDireccion, p.bodegaLat, p.bodegaLng)
        else PuntoDto("Local de ${p.nombre}", p.direccion, p.lat, p.lng)

    private fun resumen(km: Double, minutosManejo: Double, paradas: Int): ResumenRutaDto {
        val a = props.abastecimiento
        return ResumenRutaDto(
            BigDecimal(km).setScale(1, RoundingMode.HALF_UP),
            minutosManejo.roundToInt() + paradas * a.minutosPorParada,
            BigDecimal(km * a.co2KgPorKm).setScale(1, RoundingMode.HALF_UP),
        )
    }

    companion object {
        private val ACTIVAS = setOf(EstadoOrden.EMPACADA, EstadoOrden.DESPACHADA, EstadoOrden.EN_TRANSITO)
    }
}

