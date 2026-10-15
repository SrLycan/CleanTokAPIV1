package ec.cleantok.ordenes.extraccion

import ec.cleantok.comun.Textos
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

data class Linea(val pagina: Int, val texto: String)

private val GUION_SUAVE = Char(0xAD)
private val ESPACIO_DURO = Char(0xA0)

/**
 * Texto de un PDF ya limpio de ruido de diseño, con el número de página de cada línea.
 * [texto] une las líneas con `\n`; [inicioLinea] da el desplazamiento de cada línea dentro de [texto].
 */
class DocumentoPdf(
    val lineas: List<Linea>,
    val paginas: Int,
    /** Meses del plazo, recuperados de los fragmentos "(12" … "meses)" de la columna Cant que se limpiaron. */
    val vigenciaColumnaCant: Int? = null,
) {
    val texto: String = lineas.joinToString("\n") { it.texto }
    val inicioLinea: IntArray = IntArray(lineas.size).also { arr ->
        var pos = 0
        lineas.forEachIndexed { i, l -> arr[i] = pos; pos += l.texto.length + 1 }
    }

    /** Un PDF escaneado (solo imágenes) casi no tiene letras extraíbles. */
    val tieneTexto: Boolean = texto.count { it.isLetter() } >= 50

    val textoNormalizado: String by lazy { normalizarParaVerificar(texto) }

    fun lineaEn(offset: Int): Int {
        val i = inicioLinea.binarySearch(offset)
        return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
    }

    fun paginaEn(offset: Int): Int = lineas.getOrNull(lineaEn(offset))?.pagina ?: 1

    companion object {
        /** Minúsculas, sin tildes y espacios colapsados (los saltos de línea cuentan como espacio). */
        fun normalizarParaVerificar(s: String): String = Textos.normalizar(s.replace(GUION_SUAVE, ' '))
    }
}

/**
 * Lee el PDF con PDFBox (texto ordenado por posición, página por página) y limpia el ruido:
 * encabezados y pies repetidos, URL del portal, numeración "N de M", fragmentos de la columna
 * "Cant" que se cuelan en la descripción ("(12", "meses)") y guiones de corte de palabra.
 */
object LectorPdf {
    private val ruidoLineaCompleta = listOf(
        Regex("""^\d{1,2}/\d{1,2}/\d{4}\s+Cat[aá]logo electr[oó]nico\s*$"""),
        Regex("""^Cat[aá]logo electr[oó]nico(\s+https?://\S*)?\s*$"""),
        Regex("""^https?://\S*compraspublicas\.gob\.ec\S*(\s+\d+/\d+)?\s*$"""),
        Regex("""^\d+\s+de\s+\d+\s+\d{1,2}/\d{1,2}/\d{4},?\s+\d{1,2}:\d{2}\s*$"""),
        Regex("""^\d+/\d+\s*$"""),
        Regex("""^Fecha de Impresi[oó]n:.*$"""),
    )
    private val columnaCantInicio = Regex("""\s\(\d{1,2}\s*$""")
    private val columnaCantFin = Regex("""(?<!\(\d{1,2} )\b[Mm]eses\)""")

    fun leer(bytes: ByteArray): DocumentoPdf = Loader.loadPDF(bytes).use { doc ->
        val lineas = mutableListOf<Linea>()
        var meses: Int? = null
        var huboMeses = false
        // La columna "Cant" solo existe en la tabla DETALLE: antes de ella no se limpia nada.
        var enDetalle = false
        for (p in 1..doc.numberOfPages) {
            val stripper = PDFTextStripper().apply { sortByPosition = true; startPage = p; endPage = p }
            stripper.getText(doc).lines().forEach { bruta ->
                val l = bruta.replace(ESPACIO_DURO, ' ').trimEnd()
                if (l.isBlank() || ruidoLineaCompleta.any { it.matches(l.trim()) }) return@forEach
                if (l.trim() == "DETALLE") enDetalle = true
                if (!enDetalle) {
                    lineas += Linea(p, l)
                    return@forEach
                }
                if (meses == null) columnaCantInicio.find(l)?.let { meses = it.value.filter(Char::isDigit).toInt() }
                if (columnaCantFin.containsMatchIn(l)) huboMeses = true
                lineas += Linea(p, limpiarLinea(l))
            }
        }
        DocumentoPdf(unirCortes(lineas), doc.numberOfPages, meses?.takeIf { huboMeses })
    }

    fun limpiarLinea(l: String): String {
        var s = l
        // "(12" suelto al final y "meses)" sin su paréntesis de apertura: vienen de la columna Cant.
        if (columnaCantInicio.containsMatchIn(s)) s = s.replace(columnaCantInicio, "")
        // Los espacios se conservan: el marcador de sección " -    TITULO:" depende de ellos.
        s = s.replace(columnaCantFin, "")
        return s.trimEnd()
    }

    /** "pala-⏎bra" → "palabra" cuando la línea siguiente empieza en minúscula. */
    fun unirCortes(lineas: List<Linea>): List<Linea> {
        val salida = mutableListOf<Linea>()
        var i = 0
        while (i < lineas.size) {
            var actual = lineas[i]
            while (i + 1 < lineas.size && Regex("""\p{L}-$""").containsMatchIn(actual.texto) &&
                lineas[i + 1].texto.firstOrNull()?.isLowerCase() == true
            ) {
                actual = Linea(actual.pagina, actual.texto.dropLast(1) + lineas[i + 1].texto)
                i++
            }
            salida += actual
            i++
        }
        return salida
    }
}
