package ec.cleantok.abastecimiento.servicio

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Punto(val lat: Double, val lng: Double)

/**
 * Ruta abierta desde un origen que visita todas las paradas una vez.
 * Vecino más cercano para la primera solución y 2-opt para mejorarla: explicable y suficiente
 * para las pocas paradas de un reparto. Trabaja sobre una matriz de distancias (por calles si se tiene;
 * si no, en línea recta con Haversine). La matriz puede ser asimétrica (calles de un solo sentido).
 */
object OptimizadorRutas {
    private const val RADIO_TIERRA_KM = 6371.0

    fun km(a: Punto, b: Punto): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * RADIO_TIERRA_KM * asin(sqrt(h))
    }

    /** Matriz en línea recta: fila/columna 0 = origen, 1..n = paradas. */
    fun matriz(origen: Punto, paradas: List<Punto>): Array<DoubleArray> {
        val todos = listOf(origen) + paradas
        return Array(todos.size) { i -> DoubleArray(todos.size) { j -> km(todos[i], todos[j]) } }
    }

    /** Largo de recorrer [orden] (índices de paradas, desde 0) partiendo del origen. */
    fun largo(d: Array<DoubleArray>, orden: List<Int>): Double {
        var total = 0.0
        var actual = 0
        for (i in orden) {
            total += d[actual][i + 1]
            actual = i + 1
        }
        return total
    }

    /** Orden de visita optimizado (índices de paradas, desde 0). */
    fun optimizar(d: Array<DoubleArray>): List<Int> {
        val n = d.size - 1
        if (n <= 1) return (0 until n).toList()
        val orden = vecinoMasCercano(d)
        dosOpt(d, orden)
        return orden
    }

    fun largo(origen: Punto, paradas: List<Punto>, orden: List<Int>): Double = largo(matriz(origen, paradas), orden)

    fun optimizar(origen: Punto, paradas: List<Punto>): List<Int> = optimizar(matriz(origen, paradas))

    private fun vecinoMasCercano(d: Array<DoubleArray>): MutableList<Int> {
        val pendientes = (0 until d.size - 1).toMutableSet()
        val orden = mutableListOf<Int>()
        var actual = 0
        while (pendientes.isNotEmpty()) {
            val siguiente = pendientes.minBy { d[actual][it + 1] }
            orden += siguiente
            pendientes -= siguiente
            actual = siguiente + 1
        }
        return orden
    }

    /** Invierte tramos mientras alguno acorte la ruta. */
    private fun dosOpt(d: Array<DoubleArray>, orden: MutableList<Int>) {
        var mejora = true
        while (mejora) {
            mejora = false
            for (i in 0 until orden.size - 1) {
                for (k in i + 1 until orden.size) {
                    val candidata = orden.toMutableList().apply { subList(i, k + 1).reverse() }
                    if (largo(d, candidata) < largo(d, orden) - 1e-9) {
                        orden.clear()
                        orden.addAll(candidata)
                        mejora = true
                    }
                }
            }
        }
    }
}
