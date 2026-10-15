package ec.cleantok.ordenes.extraccion

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.Textos

/** Ítem tal como lo devuelve el modelo de IA (salida estructurada). */
data class ItemIa(
    val textoOriginal: String,
    val nombre: String,
    val categoria: String,
    val unidadSugerida: String,
    val presentacion: String?,
    val seccionOrigen: String,
    val abastece: String,
    val pagina: Int?,
)

/** Lo que se envía a la IA: texto enmascarado o, solo si el admin lo autorizó, el PDF completo. */
data class EntradaIa(val texto: String?, val pdf: ByteArray?)

/** Proveedor de IA intercambiable (Claude hoy). Se elige por configuración. */
interface ClienteIaMateriales {
    val disponible: Boolean

    fun extraer(entrada: EntradaIa): List<ItemIa>
}

class ExtraccionIaException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

/**
 * Datos personales que nunca salen hacia la IA: correos, RUC, cédulas, teléfonos y números de cuenta.
 * Enmascara cualquier secuencia de 7 o más dígitos (cubre cuentas y teléfonos sin formato).
 */
object Enmascarador {
    private val reglas = listOf(
        Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""") to "[CORREO]",
        Regex("""\b\d{13}\b""") to "[RUC]",
        Regex("""\b\d{10}\b""") to "[CEDULA]",
        Regex("""\b0\d{1,2}[-\s]\d{6,7}\b""") to "[TELEFONO]",
        Regex("""\b\d{2}-\s*\d{6,7}\b""") to "[TELEFONO]",
        Regex("""\b\d{7,}\b""") to "[NUMERO]",
    )

    fun enmascarar(texto: String): String = reglas.fold(texto) { acc, (r, rep) -> r.replace(acc, rep) }
}

/**
 * Extracción con IA como respaldo del parser. Arma una entrada mínima (solo las secciones relevantes,
 * enmascaradas) y convierte la respuesta en propuestas. La verificación literal se hace después, igual
 * que para el parser.
 */
class ExtractorIa(private val cliente: ClienteIaMateriales, private val sercop: ExtractorSercop) {

    fun entrada(doc: DocumentoPdf, permitirPdf: Boolean): EntradaIa {
        if (!doc.tieneTexto) {
            if (!permitirPdf) throw ExtraccionIaException("El PDF no tiene capa de texto y no se autorizó enviarlo completo a la IA")
            return EntradaIa(texto = null, pdf = null)
        }
        return EntradaIa(texto = Enmascarador.enmascarar(textoRelevante(doc)), pdf = null)
    }

    /** Secciones de materiales, herramientas y cláusulas de la entidad; si no se detectan, todo el texto. */
    fun textoRelevante(doc: DocumentoPdf): String {
        val partes = mutableListOf<String>()
        sercop.secciones(doc)
            .filter { s -> RELEVANTES.any { s.clave.contains(it) } }
            .forEach { s -> partes += "## ${s.titulo}\n" + doc.texto.substring(s.inicio, s.fin).trim() }
        val oraciones = doc.texto.split(Regex("""(?<=\.)\s+"""))
        oraciones.forEachIndexed { i, o ->
            if (CLAUSULA.containsMatchIn(Textos.normalizar(o))) {
                partes += oraciones.subList(i, minOf(oraciones.size, i + 2)).joinToString(" ")
            }
        }
        return if (partes.isEmpty()) doc.texto else partes.distinct().joinToString("\n\n")
    }

    fun extraer(doc: DocumentoPdf, pdf: ByteArray, permitirPdf: Boolean): List<ItemPropuesto> {
        val base = entrada(doc, permitirPdf)
        val entrada = if (base.texto == null) base.copy(pdf = pdf) else base
        return cliente.extraer(entrada).mapNotNull { convertir(it) }
    }

    private fun convertir(i: ItemIa): ItemPropuesto? {
        if (i.nombre.isBlank()) return null
        val seccion = runCatching { SeccionOrigen.valueOf(i.seccionOrigen) }.getOrDefault(SeccionOrigen.MATERIALES)
            .takeIf { it != SeccionOrigen.MANUAL } ?: SeccionOrigen.MATERIALES
        val herramienta = seccion in setOf(SeccionOrigen.HERRAMIENTAS, SeccionOrigen.MAQUINARIA, SeccionOrigen.EQUIPAMIENTO)
        return ItemPropuesto(
            textoOriginal = i.textoOriginal.replace(Regex("\\s+"), " ").trim().ifBlank { null },
            nombre = Textos.limpiar(i.nombre).replaceFirstChar { it.uppercase() },
            categoria = runCatching { Categoria.valueOf(i.categoria) }.getOrDefault(Categoria.OTRO),
            unidad = runCatching { Unidad.valueOf(i.unidadSugerida) }.getOrDefault(Unidad.UNIDAD),
            presentacion = i.presentacion?.trim()?.ifBlank { null },
            seccion = seccion,
            abastece = runCatching { Abastece.valueOf(i.abastece) }.getOrDefault(Abastece.EMPRESA_LIMPIEZA),
            esConsumible = !herramienta,
            pagina = i.pagina?.takeIf { it > 0 },
            origen = OrigenMaterial.IA,
            seleccionado = !herramienta,
        )
    }

    companion object {
        private val RELEVANTES = listOf("materiales", "herramientas", "maquinaria", "equipamiento", "insumos", "productos")
        private val CLAUSULA = Regex("""no incluye|proporcionad|suministrad|provee|proveera""")

        const val INSTRUCCIONES = """Eres un asistente que extrae la lista de materiales e insumos de limpieza de una orden de compra o contrato de servicio de limpieza en Ecuador.

Reglas obligatorias:
- Devuelve SOLO materiales, insumos, herramientas o maquinaria que aparezcan literalmente en el documento. No inventes ni completes nada.
- En "texto_original" copia el fragmento tal cual aparece en el documento (mismas palabras, mismo orden), sin corregirlo.
- En "nombre" escribe un nombre de producto claro en español, con mayúscula inicial y tildes correctas.
- No deduzcas cantidades: el documento no las trae y no las necesitamos.
- No incluyas áreas o superficies a limpiar (pisos, baños, paredes, techos, puertas, ventanas, mobiliario…), tareas, horarios ni personal.
- "abastece" es ENTIDAD_CONTRATANTE cuando el documento dice que la entidad contratante provee, suministra o repone ese producto (o todos los materiales); en otro caso, EMPRESA_LIMPIEZA.
- "seccion_origen": MATERIALES para insumos y materiales; HERRAMIENTAS, MAQUINARIA o EQUIPAMIENTO para equipos; CLAUSULA_ENTIDAD para productos que solo aparecen en una cláusula que dice que los provee la entidad.
- "pagina": número de página donde aparece, o 0 si no lo sabes. "presentacion": medida o tamaño (por ejemplo "600 ml", "23x28"), o cadena vacía.
- Los datos personales del documento están enmascarados como [CORREO], [RUC], [TELEFONO], [NUMERO]: ignóralos."""
    }
}
