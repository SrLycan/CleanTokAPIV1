package ec.cleantok.ordenes.extraccion

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.Textos
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Parser determinista de órdenes de compra por catálogo electrónico del SERCOP (sin IA).
 *
 * Secciones: líneas que empiezan con " -    TITULO EN MAYÚSCULAS:". De `MATERIALES` (o de la parte
 * "MATERIALES Y PRODUCTOS UTILIZADOS EN LA DESINFECCIÓN" dentro de `HERRAMIENTAS Y MATERIALES`) salen los
 * materiales; de `HERRAMIENTAS`, `MAQUINARIA` y `EQUIPAMIENTO`, sugerencias no seleccionadas.
 * Las cláusulas de exclusión ("no incluye artículos de aseo como: …", "Reposición de material proporcionado
 * por la entidad (…)", "Todos los materiales … serán suministrados por la Entidad") marcan quién abastece.
 */
class ExtractorSercop(private val lexico: Lexico) {

    data class Seccion(val titulo: String, val inicioLinea: Int, val inicio: Int, val fin: Int) {
        val clave: String = Textos.normalizar(titulo)
    }

    /** Fragmento [ini, fin) del texto del documento y lo que se sabe de él. */
    private data class Frag(val ini: Int, val fin: Int, val motivos: List<String> = emptyList())

    fun esSercop(doc: DocumentoPdf): Boolean =
        "orden de compra por catalogo electronico" in doc.textoNormalizado || "compraspublicas.gob.ec" in doc.texto

    fun extraer(doc: DocumentoPdf): ResultadoExtraccion {
        val avisos = mutableListOf<String>()
        val secciones = secciones(doc)
        val materiales = mutableListOf<ItemPropuesto>()
        val herramientas = mutableListOf<ItemPropuesto>()

        for (s in secciones) {
            when {
                s.clave == "materiales" -> materiales += materiales(doc, s.inicio, s.fin)
                s.clave.startsWith("herramientas y materiales") -> {
                    val sub = SUBENCABEZADO.find(doc.texto, s.inicio)?.takeIf { it.range.first < s.fin }
                    if (sub != null) {
                        herramientas += herramientas(doc, s.inicio, sub.range.first, SeccionOrigen.HERRAMIENTAS)
                        materiales += materiales(doc, sub.range.last + 1, s.fin)
                    } else {
                        materiales += materiales(doc, s.inicio, s.fin)
                    }
                }
                s.clave.startsWith("herramientas") -> herramientas += herramientas(doc, s.inicio, s.fin, SeccionOrigen.HERRAMIENTAS)
                s.clave.startsWith("maquinaria") -> herramientas += herramientas(doc, s.inicio, s.fin, SeccionOrigen.MAQUINARIA)
                s.clave.startsWith("equipamiento") -> herramientas += herramientas(doc, s.inicio, s.fin, SeccionOrigen.EQUIPAMIENTO)
            }
        }
        // Variante sin sección propia: el subencabezado suelto, hasta la siguiente sección o "Subtotal".
        if (materiales.isEmpty()) {
            SUBENCABEZADO.find(doc.texto)?.let { m ->
                val fin = secciones.firstOrNull { it.inicioLinea > m.range.first }?.inicioLinea ?: finDelDetalle(doc, m.range.first)
                materiales += materiales(doc, m.range.last + 1, fin)
            }
        }
        marcarDuplicados(materiales)

        if (TODOS_ENTIDAD.containsMatchIn(doc.texto)) {
            materiales.forEach { it.abastece = Abastece.ENTIDAD_CONTRATANTE }
            avisos += "El documento indica que la entidad contratante suministra todos los materiales"
        }
        val clausulas = clausulas(doc)
        for (c in clausulas) {
            val existente = materiales.firstOrNull { clave(it.nombre) == clave(c.nombre) }
            if (existente != null) {
                existente.abastece = Abastece.ENTIDAD_CONTRATANTE
                existente.motivos += "una cláusula indica que lo provee la entidad"
            } else {
                materiales += c
            }
        }
        return ResultadoExtraccion(cabecera(doc), materiales + herramientas, avisos)
    }

    // ---------------------------------------------------------------- secciones

    fun secciones(doc: DocumentoPdf): List<Seccion> {
        data class Marca(val inicioLinea: Int, val inicioTitulo: Int, val titulo: String, val dosPuntos: Int)
        val marcas = doc.lineas.indices.mapNotNull { i ->
            val m = MARCA.find(doc.lineas[i].texto) ?: return@mapNotNull null
            val inicioTitulo = doc.inicioLinea[i] + m.groups[1]!!.range.first
            val dosPuntos = doc.texto.indexOf(':', inicioTitulo)
            if (dosPuntos < 0 || dosPuntos - inicioTitulo > 160) return@mapNotNull null
            val titulo = doc.texto.substring(inicioTitulo, dosPuntos).replace(Regex("\\s+"), " ").trim()
            if (!esTitulo(titulo)) return@mapNotNull null
            Marca(doc.inicioLinea[i], inicioTitulo, titulo, dosPuntos)
        }
        return marcas.mapIndexed { k, m ->
            val fin = marcas.getOrNull(k + 1)?.inicioLinea ?: finDelDetalle(doc, m.dosPuntos)
            Seccion(m.titulo, m.inicioLinea, m.dosPuntos + 1, fin)
        }
    }

    private fun esTitulo(t: String): Boolean {
        val letras = t.filter { it.isLetter() }
        return letras.length >= 4 && letras.count { it.isUpperCase() } >= letras.length * 0.75
    }

    /** La última sección termina en la línea "Subtotal" (o al final del texto). */
    private fun finDelDetalle(doc: DocumentoPdf, desde: Int): Int {
        val i = doc.lineas.indices.firstOrNull { doc.inicioLinea[it] > desde && doc.lineas[it].texto.trimStart().startsWith("Subtotal") }
        return i?.let { doc.inicioLinea[it] } ?: doc.texto.length
    }

    // ---------------------------------------------------------------- materiales

    fun materiales(doc: DocumentoPdf, ini: Int, fin: Int): List<ItemPropuesto> {
        val bloque = doc.texto.substring(ini, fin)
        val conVinetas = '•' in bloque
        // Con viñetas, lo que precede a la primera viñeta es un preámbulo ("Desinfección del área crítica…").
        val inicio = if (conVinetas) doc.texto.indexOf('•', ini) else ini
        val corte = FIN_LISTA.find(doc.texto, inicio)?.takeIf { it.range.first < fin }?.range?.first?.plus(1) ?: fin
        val frags = dividir(doc, inicio, corte, if (conVinetas) SEP_VINETA else SEP_COMA)
            .mapNotNull { recortar(doc, it, cortarEnDosPuntos = conVinetas) }
            .let { sinPuntoFinal(doc, it) }
            .flatMap { separarComaFaltante(doc, it) }
        return construir(doc, frags, SeccionOrigen.MATERIALES, fusionar = !conVinetas)
    }

    private fun construir(doc: DocumentoPdf, frags: List<Frag>, seccion: SeccionOrigen, fusionar: Boolean): List<ItemPropuesto> {
        val unidos = mutableListOf<Frag>()
        for (f in frags) {
            val t = doc.texto.substring(f.ini, f.fin)
            if (lexico.empiezaConArea(t)) continue
            if (fusionar && !lexico.tieneSustantivo(t) && unidos.isNotEmpty()) {
                val prev = unidos.removeAt(unidos.lastIndex)
                unidos += Frag(prev.ini, f.fin, prev.motivos + "posible fusión")
            } else {
                unidos += f
            }
        }
        return unidos.map { f ->
            val original = doc.texto.substring(f.ini, f.fin)
            val item = item(original, seccion, doc.paginaEn(f.ini), esConsumible = true, seleccionado = true)
            f.motivos.forEach { item.bajarA(Confianza.MEDIA, it) }
            if (!lexico.tieneSustantivo(item.nombre)) {
                val suelta = original.trim().split(Regex("\\s+")).size == 1 && original.trim().first().isLowerCase()
                item.bajarA(Confianza.BAJA, if (suelta) "palabra suelta sin producto reconocido" else "sin sustantivo de producto reconocido")
            }
            item
        }
    }

    /** Arma el ítem: nombre normalizado, presentación, categoría y unidad sugeridas. No toca el texto original. */
    fun item(original: String, seccion: SeccionOrigen, pagina: Int?, esConsumible: Boolean, seleccionado: Boolean): ItemPropuesto {
        val nombre = nombreSugerido(original)
        val presentacion = PRESENTACION.find(nombre)?.value?.trim()
        val coincidencia = lexico.primerTermino(nombre)
        var categoria = coincidencia?.termino?.categoria ?: Categoria.OTRO
        var unidad = coincidencia?.termino?.unidad ?: Unidad.UNIDAD
        if (seccion == SeccionOrigen.MAQUINARIA) categoria = Categoria.MAQUINARIA
        // Un químico con envase indicado (600 ml, 450 gr) se cuenta por unidades.
        if (categoria == Categoria.QUIMICO && presentacion != null && ENVASE.containsMatchIn(presentacion)) unidad = Unidad.UNIDAD
        if (categoria == Categoria.QUIMICO && "pastilla" in Textos.normalizar(nombre)) unidad = Unidad.UNIDAD
        return ItemPropuesto(
            textoOriginal = original.replace(Regex("\\s+"), " ").trim(),
            nombre = nombre,
            categoria = categoria,
            unidad = unidad,
            presentacion = presentacion,
            seccion = seccion,
            abastece = Abastece.EMPRESA_LIMPIEZA,
            esConsumible = esConsumible,
            pagina = pagina,
            origen = OrigenMaterial.PARSER,
            seleccionado = seleccionado,
        )
    }

    fun nombreSugerido(original: String): String {
        var s = original
            .replace(Regex("(?<!\\d),|,(?!\\d)|[;•]"), " ")
            .replace(Regex("(?<=\\p{L})\\*|^\\s*[*\\-–]\\s*"), "")
            .replace(Regex("\\s+"), " ").trim()
        if (s.endsWith(".") && !ABREVIATURA.containsMatchIn(s)) s = s.dropLast(1).trim()
        // Paréntesis final: se quita si es un área ("(baños)"); si aclara un alias ("servilletas (papel toalla)"), se usa el alias.
        PARENTESIS_FINAL.find(s)?.let { m ->
            val antes = s.substring(0, m.range.first).trim()
            val dentro = m.groupValues[1].trim()
            s = when {
                lexico.contieneArea(dentro) -> antes
                antes.split(" ").size == 1 && dentro.split(" ").size >= 2 && lexico.tieneSustantivo(dentro) -> dentro
                else -> s
            }
        }
        val todoMayusculas = s.none { it.isLowerCase() }
        s = s.split(" ").joinToString(" ") { p ->
            val esSigla = !todoMayusculas && p.length >= 2 && p.any { it.isLetter() } && p.none { it.isLowerCase() }
            if (esSigla) p else p.lowercase()
        }
        s = lexico.corregirTildes(s)
        return s.replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- herramientas y otras secciones

    fun herramientas(doc: DocumentoPdf, ini: Int, fin: Int, seccion: SeccionOrigen): List<ItemPropuesto> =
        dividir(doc, ini, fin, SEP_HERRAMIENTA)
            .mapNotNull { recortar(doc, it, cortarEnDosPuntos = false) }
            .mapNotNull { f ->
                // "Coche escurridor 1 por cada 3000 m²" → "Coche escurridor"
                val t = doc.texto.substring(f.ini, f.fin)
                val regla = REGLA_CANTIDAD.find(t)
                val g = if (regla != null) recortar(doc, Frag(f.ini, f.ini + regla.range.first), false) else f
                g?.takeIf {
                    val texto = doc.texto.substring(it.ini, it.fin)
                    lexico.tieneSustantivo(texto) && !lexico.empiezaConArea(texto)
                }
            }
            .map { f -> item(doc.texto.substring(f.ini, f.fin), seccion, doc.paginaEn(f.ini), esConsumible = false, seleccionado = false) }
            .distinctBy { Textos.normalizar(it.nombre) }

    // ---------------------------------------------------------------- cláusulas de la entidad

    fun clausulas(doc: DocumentoPdf): List<ItemPropuesto> {
        val rangos = mutableListOf<IntRange>()
        NO_INCLUYE.findAll(doc.texto).forEach { rangos += it.groups[1]!!.range }
        REPOSICION.findAll(doc.texto).forEach { m ->
            val cierre = parentesisDeCierre(doc.texto, m.range.last)
            if (cierre > m.range.last + 1) rangos += (m.range.last + 1) until cierre
        }
        return rangos.sortedBy { it.first }.flatMap { r ->
            val frags = sinPuntoFinal(doc, dividir(doc, r.first, r.last + 1, SEP_COMA).mapNotNull { recortar(doc, it, false) })
            construir(doc, frags, SeccionOrigen.CLAUSULA_ENTIDAD, fusionar = true)
        }.onEach { it.abastece = Abastece.ENTIDAD_CONTRATANTE }
            .distinctBy { clave(it.nombre) }
    }

    /** `abre` es la posición del "(" de apertura. */
    private fun parentesisDeCierre(t: String, abre: Int): Int {
        var prof = 0
        for (i in abre until minOf(t.length, abre + 1000)) {
            if (t[i] == '(') prof++
            if (t[i] == ')' && --prof == 0) return i
        }
        return -1
    }

    // ---------------------------------------------------------------- reglas sobre fragmentos

    /** Divide [ini, fin) por el separador, sin cortar dentro de paréntesis. */
    private fun dividir(doc: DocumentoPdf, ini: Int, fin: Int, sep: Regex): List<Frag> {
        val t = doc.texto
        val res = mutableListOf<Frag>()
        var inicio = ini
        var prof = 0
        var i = ini
        while (i < fin) {
            when (t[i]) {
                '(' -> prof++
                ')' -> if (prof > 0) prof--
            }
            if (prof == 0) {
                val m = sep.matchAt(t, i)
                if (m != null && m.value.isNotEmpty() && m.range.last < fin) {
                    res += Frag(inicio, i)
                    i = m.range.last + 1
                    inicio = i
                    continue
                }
            }
            i++
        }
        res += Frag(inicio, fin)
        return res
    }

    /** Quita espacios, viñetas, "y " inicial y puntuación final (salvo abreviaturas como "lt."). */
    private fun recortar(doc: DocumentoPdf, f: Frag, cortarEnDosPuntos: Boolean): Frag? {
        val t = doc.texto
        var a = f.ini
        var b = f.fin
        while (a < b && (t[a].isWhitespace() || t[a] in "*-–•")) a++
        if (cortarEnDosPuntos) {
            val c = t.indexOf(':', a)
            if (c in a until b) b = c
        }
        Regex("^(?:y|e)\\s+", RegexOption.IGNORE_CASE).find(t.substring(a, b))?.let { a += it.value.length }
        while (b > a && (t[b - 1].isWhitespace() || t[b - 1] in ",;:*")) b--
        if (b > a && t[b - 1] == '.' && !ABREVIATURA.containsMatchIn(t.substring(a, b))) b--
        while (b > a && t[b - 1].isWhitespace()) b--
        return if (b > a && t.substring(a, b).any { it.isLetter() }) Frag(a, b, f.motivos) else null
    }

    /** El punto al final de la lista cierra la enumeración aunque siga a una abreviatura ("… de 35 cm."). */
    private fun sinPuntoFinal(doc: DocumentoPdf, frags: List<Frag>): List<Frag> {
        val ultimo = frags.lastOrNull() ?: return frags
        return if (doc.texto[ultimo.fin - 1] == '.') frags.dropLast(1) + ultimo.copy(fin = ultimo.fin - 1) else frags
    }

    /** "Hipoclorito de sodio al 10% Insecticida" → dos ítems: tras una medida aparece otro sustantivo. */
    private fun separarComaFaltante(doc: DocumentoPdf, f: Frag): List<Frag> {
        val texto = doc.texto.substring(f.ini, f.fin)
        val m = COMA_FALTANTE.findAll(texto).firstOrNull { lexico.esSustantivo(it.groupValues[2]) } ?: return listOf(f)
        val primero = Frag(f.ini, f.ini + m.groups[1]!!.range.last + 1, f.motivos + "posible separación")
        val resto = Frag(f.ini + m.groups[2]!!.range.first, f.fin, f.motivos + "posible separación")
        return listOf(primero) + separarComaFaltante(doc, resto)
    }

    /** Clave para detectar duplicados: palabras sin medidas ni conectores. */
    fun clave(nombre: String): String =
        lexico.palabras(nombre).filter { it !in CONECTORES && it !in UNIDADES_MEDIDA && !it.first().isDigit() }.joinToString(" ")

    private fun marcarDuplicados(items: List<ItemPropuesto>) {
        val vistos = mutableMapOf<String, Int>()
        items.forEachIndexed { i, it ->
            val k = clave(it.nombre)
            if (k.isBlank()) return@forEachIndexed
            val previo = vistos[k]
            if (previo != null) {
                it.duplicadoDe = previo
                it.bajarA(Confianza.MEDIA, "posible duplicado de \"${items[previo].nombre}\"")
            } else {
                vistos[k] = i
            }
        }
    }

    // ---------------------------------------------------------------- cabecera

    fun cabecera(doc: DocumentoPdf): Cabecera {
        val t = doc.texto
        val numero = NUMERO_ORDEN.find(t)?.let { "CE-" + it.groupValues[1] }
            ?: if ("CE-" in t) NUMERO_SUELTO.find(t)?.let { "CE-" + it.groupValues[1] } else null
        val fecha = (EMISION.find(t) ?: FECHA.find(t))?.groupValues?.get(1)?.let {
            runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("dd-MM-yyyy")) }.getOrNull()
        }
        var descripcion: String? = null
        var cantidad: BigDecimal? = null
        var vigencia: Int? = null
        val iCab = doc.lineas.indexOfFirst { it.texto.trimStart().startsWith("CPC") && "Descripci" in it.texto }
        if (iCab >= 0) {
            val iDet = (iCab + 1 until minOf(doc.lineas.size, iCab + 6)).firstOrNull { DETALLE.containsMatchIn(doc.lineas[it].texto) }
            if (iDet != null) {
                val m = DETALLE.find(doc.lineas[iDet].texto)!!
                descripcion = m.groupValues[2].trim()
                cantidad = m.groupValues[3].replace(".", "").replace(",", ".").toBigDecimalOrNull()
                val siguiente = doc.lineas.getOrNull(iDet + 1)?.texto
                if (siguiente != null && !MARCA.containsMatchIn(siguiente)) {
                    VIGENCIA_EN_LINEA.find(siguiente)?.let { vigencia = it.groupValues[1].toInt() }
                    val cont = siguiente.replace(Regex("""\s*\(\d{1,2}\b.*$"""), "").replace(Regex("""(\s+[\d-]+)+$"""), "").trim()
                    if (cont.firstOrNull()?.isLetter() == true) descripcion = "$descripcion $cont"
                }
            }
        }
        if (vigencia == null) vigencia = doc.vigenciaColumnaCant
        return Cabecera(numero, entidad(doc), fecha, cantidad, descripcion, vigencia)
    }

    private fun entidad(doc: DocumentoPdf): String? {
        val ini = doc.lineas.indexOfFirst { "DATOS DE LA ENTIDAD CONTRATANTE" in it.texto }
        if (ini < 0) return null
        val fin = (ini + 1 until doc.lineas.size).firstOrNull { doc.lineas[it].texto.trimStart().startsWith("Persona que") } ?: return null
        val tokens = (ini + 1 until fin).flatMap { doc.lineas[it].texto.split(Regex("\\s+")) }
            .filter { tok -> tok.isNotBlank() && tok.none { it.isDigit() } && !tok.endsWith(":") && tok.any { it.isLetter() } && tok.none { it.isLowerCase() } }
        return tokens.joinToString(" ").ifBlank { null }
    }

    companion object {
        private val MARCA = Regex("""^\s*-\s{2,}(\S)""")
        private val SUBENCABEZADO = Regex(
            """MATERIALES\s+Y\s+PRODUCTOS\s+UTILIZADOS\s+EN\s+LA\s+DESINFECCI[OÓ]N\s*:""",
            RegexOption.IGNORE_CASE,
        )
        /** Fin de una lista: punto seguido de un subtítulo en mayúsculas ("… críticas. ROTACIÓN DE PRODUCTOS"). */
        private val FIN_LISTA = Regex("""\.\s+(?=[A-ZÁÉÍÓÚÑ]{3,}(?:\s+[A-ZÁÉÍÓÚÑ]{2,})+)""")
        private val SEP_COMA = Regex("""(?<!\d),|,(?!\d)|;|•|(?<!\p{L})\*""")
        private val SEP_VINETA = Regex("""•|\s\*\s""")
        /** Además: salto de línea tras un subtítulo en mayúsculas ("HERRAMIENTAS CANTIDAD⏎Coche…"). */
        private val SEP_HERRAMIENTA = Regex("""[•;:]|(?<!\d),|,(?!\d)|(?<!\p{L})\*|\s-\s|\.\s+(?=\p{Lu})|(?<=\p{Lu}{3})\n(?=\p{Lu}\p{Ll})""")
        private val REGLA_CANTIDAD = Regex("""\s+\d+\s+por\s+cada\b|\s+en\s+caso\s+de\s+que\b""", RegexOption.IGNORE_CASE)
        private val ABREVIATURA = Regex("""\b(?:lt|gr|cm|ml|mtrs|mts|kg)\.$""", RegexOption.IGNORE_CASE)
        private val PARENTESIS_FINAL = Regex("""\s*\(([^()]+)\)\s*$""")
        val PRESENTACION = Regex(
            """\d+(?:[.,]\d+)?\s?%|\d+\s?x\s?\d+|\d+(?:[.,]\d+)?\s?(?:ml|lt|litros?|l|gr|g|kg|cm|mtrs|m|gal[oó]n(?:es)?)\b\.?""",
            RegexOption.IGNORE_CASE,
        )
        private val ENVASE = Regex("""\b(?:ml|gr|g|kg)\b""", RegexOption.IGNORE_CASE)
        private val COMA_FALTANTE = Regex(
            """(\d+(?:[.,]\d+)?\s?(?:%|ml|lt|litros?|l|gr|g|kg|cm|mtrs|m)\.?)\s+(\p{L}+)""",
            RegexOption.IGNORE_CASE,
        )
        private val CONECTORES = setOf("de", "del", "para", "con", "al", "en", "el", "la", "los", "las", "y", "a")
        private val UNIDADES_MEDIDA = setOf("ml", "lt", "l", "litro", "litros", "gr", "g", "kg", "cm", "mtrs", "mts", "m", "x")

        private val NO_INCLUYE = Regex(
            """no\s+incluye\s+(?:los\s+|las\s+)?(?:art[ií]culos|productos|insumos|materiales)[^:.]{0,80}?como\s*:?\s*([^.]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val REPOSICION = Regex(
            """reposici[oó]n\s+de\s+(?:material(?:es)?|insumos)\s+proporcionad[oa]s?\s+por\s+la\s+entidad\s*\(""",
            RegexOption.IGNORE_CASE,
        )
        private val TODOS_ENTIDAD = Regex(
            """todos\s+los\s+materiales[^.]{0,80}?suministrad[oa]s\s+por\s+la\s+entidad""",
            RegexOption.IGNORE_CASE,
        )

        private val NUMERO_ORDEN = Regex("""CE-\s?(\d{14})""")
        private val NUMERO_SUELTO = Regex("""\b(20\d{12})\b""")
        private val EMISION = Regex("""emisi[oó]n:\s*(\d{2}-\d{2}-\d{4})""")
        private val FECHA = Regex("""\b(\d{2}-\d{2}-\d{4})\b""")
        private val DETALLE = Regex("""^(\d{9})\s+(.+?)\s+(\d[\d.]*)\s+\d+,\d+\s""")
        private val VIGENCIA_EN_LINEA = Regex("""\((\d{1,2})\b""")
    }
}
