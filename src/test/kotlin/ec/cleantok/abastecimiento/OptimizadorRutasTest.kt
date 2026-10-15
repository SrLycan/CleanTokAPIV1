package ec.cleantok.abastecimiento

import ec.cleantok.abastecimiento.servicio.OptimizadorRutas
import ec.cleantok.abastecimiento.servicio.Punto
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimizadorRutasTest {

    @Test
    fun `Haversine entre Quito y Guayaquil ronda los 270 km en línea recta`() {
        val km = OptimizadorRutas.km(Punto(-0.1807, -78.4678), Punto(-2.1894, -79.8891))
        assertTrue(abs(km - 270) < 10, "km = $km")
    }

    @Test
    fun `paradas en línea se visitan en orden y la ruta optimizada nunca es más larga que la base`() {
        val origen = Punto(0.0, 0.0)
        // Desordenadas a propósito: 3, 1, 4, 2 (cada una 0,1° más al este).
        val paradas = listOf(Punto(0.0, 0.3), Punto(0.0, 0.1), Punto(0.0, 0.4), Punto(0.0, 0.2))
        val optima = OptimizadorRutas.optimizar(origen, paradas)
        assertEquals(listOf(1, 3, 0, 2), optima)
        val base = OptimizadorRutas.largo(origen, paradas, paradas.indices.toList())
        val opt = OptimizadorRutas.largo(origen, paradas, optima)
        assertTrue(opt < base, "optimizada $opt vs base $base")
    }

    @Test
    fun `2-opt corrige un cruce que deja el vecino más cercano`() {
        val origen = Punto(0.0, 0.0)
        val paradas = listOf(Punto(0.0, 1.0), Punto(1.0, 1.0), Punto(1.0, 0.1), Punto(0.05, 0.5), Punto(0.9, 0.55))
        val optima = OptimizadorRutas.optimizar(origen, paradas)
        val todas = permutaciones(paradas.indices.toList())
        val mejor = todas.minOf { OptimizadorRutas.largo(origen, paradas, it) }
        // Con 5 paradas, 2-opt debe quedar a menos de 5 % del óptimo exacto.
        assertTrue(OptimizadorRutas.largo(origen, paradas, optima) <= mejor * 1.05)
        assertEquals(paradas.indices.toSet(), optima.toSet())
    }

    private fun permutaciones(xs: List<Int>): List<List<Int>> =
        if (xs.size <= 1) listOf(xs) else xs.flatMap { x -> permutaciones(xs - x).map { listOf(x) + it } }
}
