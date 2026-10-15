package ec.cleantok.ordenes.extraccion

import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.Textos

/** Entrada del léxico: término normalizado (una o varias palabras) con su categoría y unidad sugeridas. */
data class TerminoLexico(val palabras: List<String>, val categoria: Categoria, val unidad: Unidad)

/** Coincidencia de un término en un nombre: posición (en palabras) y término. */
data class Coincidencia(val posicion: Int, val termino: TerminoLexico)

/**
 * Léxico configurable (archivos en `resources/ordenes/`): sustantivos de producto, áreas que no son
 * materiales y corrección de tildes. Sin dependencias de Spring para poder usarlo en pruebas unitarias.
 */
class Lexico(
    private val terminos: List<TerminoLexico>,
    private val areas: Set<String>,
    private val tildes: Map<String, String>,
) {
    /** Palabras normalizadas de un texto (letras y dígitos). */
    fun palabras(texto: String): List<String> =
        Textos.normalizar(texto).split(Regex("[^a-z0-9ñ]+")).filter { it.isNotBlank() }

    /** Acepta singular y plural (+s, +es). */
    private fun coincidePalabra(palabra: String, termino: String): Boolean =
        palabra == termino || palabra == termino + "s" || palabra == termino + "es"

    /** Primera coincidencia por posición; a igual posición gana la frase más larga. */
    fun primerTermino(texto: String): Coincidencia? {
        val ps = palabras(texto)
        var mejor: Coincidencia? = null
        for (i in ps.indices) {
            for (t in terminos) {
                if (i + t.palabras.size > ps.size) continue
                val ok = t.palabras.indices.all { k ->
                    if (k == t.palabras.lastIndex) coincidePalabra(ps[i + k], t.palabras[k]) else ps[i + k] == t.palabras[k]
                }
                if (ok && (mejor == null || mejor.posicion > i || (mejor.posicion == i && mejor.termino.palabras.size < t.palabras.size))) {
                    mejor = Coincidencia(i, t)
                }
            }
            if (mejor != null && mejor.posicion == i) return mejor
        }
        return mejor
    }

    fun tieneSustantivo(texto: String): Boolean = primerTermino(texto) != null

    fun esSustantivo(palabra: String): Boolean =
        terminos.any { it.palabras.size == 1 && coincidePalabra(Textos.normalizar(palabra), it.palabras[0]) }

    fun esArea(palabra: String): Boolean {
        val p = Textos.normalizar(palabra)
        return areas.any { coincidePalabra(p, it) }
    }

    /** ¿El texto empieza con un área (piso, baño, pared…)? */
    fun empiezaConArea(texto: String): Boolean = palabras(texto).firstOrNull()?.let(::esArea) ?: false

    fun contieneArea(texto: String): Boolean = palabras(texto).any(::esArea)

    /** Corrige tildes palabra por palabra conservando mayúsculas iniciales. */
    fun corregirTildes(texto: String): String = texto.split(" ").joinToString(" ") { palabra ->
        val nucleo = palabra.trimEnd('.', ',', ';', ':', ')')
        val sufijo = palabra.substring(nucleo.length)
        val corregida = tildes[nucleo.lowercase()] ?: return@joinToString palabra
        val conCaso = if (nucleo.firstOrNull()?.isUpperCase() == true) corregida.replaceFirstChar { it.uppercase() } else corregida
        conCaso + sufijo
    }

    companion object {
        fun desdeClasspath(): Lexico {
            fun lineas(recurso: String): List<String> =
                (Lexico::class.java.getResourceAsStream("/ordenes/$recurso") ?: error("Falta el recurso /ordenes/$recurso"))
                    .bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

            val terminos = lineas("lexico.tsv").map { l ->
                val (t, c, u) = l.split("|")
                TerminoLexico(Textos.normalizar(t).split(" "), Categoria.valueOf(c), Unidad.valueOf(u))
            }
            val areas = lineas("areas.txt").map(Textos::normalizar).toSet()
            val tildes = lineas("tildes.tsv").associate { l -> l.split("|").let { it[0] to it[1] } }
            return Lexico(terminos, areas, tildes)
        }
    }
}
