package ec.cleantok.comun

import java.text.Normalizer

object Textos {
    private val marcas = Regex("\\p{M}+")
    private val espacios = Regex("\\s+")

    /** Minúsculas, sin tildes y con espacios colapsados. Base de comparación de nombres. */
    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marcas, "").lowercase().replace(espacios, " ").trim()

    /** Recorta y colapsa espacios conservando mayúsculas y tildes. */
    fun limpiar(s: String): String = s.replace(espacios, " ").trim()
}
