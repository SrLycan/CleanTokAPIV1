package ec.cleantok.ordenes.servicio

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.ordenes.dominio.MetodoExtraccion
import ec.cleantok.ordenes.extraccion.Cabecera
import ec.cleantok.ordenes.extraccion.ClienteIaMateriales
import ec.cleantok.ordenes.extraccion.Confianza
import ec.cleantok.ordenes.extraccion.DocumentoPdf
import ec.cleantok.ordenes.extraccion.ExtraccionIaException
import ec.cleantok.ordenes.extraccion.ExtractorIa
import ec.cleantok.ordenes.extraccion.ExtractorSercop
import ec.cleantok.ordenes.extraccion.FormatoOrden
import ec.cleantok.ordenes.extraccion.ItemPropuesto
import ec.cleantok.ordenes.extraccion.Lexico
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration
class ConfiguracionExtraccion {
    @Bean
    fun lexico(): Lexico = Lexico.desdeClasspath()

    @Bean
    fun extractorSercop(lexico: Lexico) = ExtractorSercop(lexico)

    @Bean
    fun extractorIa(cliente: ClienteIaMateriales, sercop: ExtractorSercop) = ExtractorIa(cliente, sercop)
}

data class Analisis(
    val formato: FormatoOrden,
    val cabecera: Cabecera,
    val items: List<ItemPropuesto>,
    val metodo: MetodoExtraccion?,
    val avisos: List<String>,
)

/**
 * Las cuatro capas contra errores: parser determinista, IA como respaldo, verificación literal contra el
 * texto del PDF y, después, revisión obligatoria del admin (nunca se crean productos sin confirmar).
 */
@Component
class AnalizadorOrdenes(
    private val sercop: ExtractorSercop,
    private val ia: ExtractorIa,
    private val cliente: ClienteIaMateriales,
) {
    fun analizar(doc: DocumentoPdf, pdf: ByteArray, permitirPdf: Boolean, forzarIa: Boolean): Analisis {
        val avisos = mutableListOf<String>()
        val formato = if (doc.tieneTexto && sercop.esSercop(doc)) FormatoOrden.SERCOP else FormatoOrden.DESCONOCIDO
        var items = emptyList<ItemPropuesto>()
        var cabecera = Cabecera()
        var metodo: MetodoExtraccion? = null

        if (!doc.tieneTexto) {
            avisos += "El PDF no tiene capa de texto (parece escaneado): ningún ítem se puede verificar, revísalos uno por uno"
        }
        if (formato == FormatoOrden.SERCOP) {
            val r = sercop.extraer(doc)
            items = r.items
            cabecera = r.cabecera
            avisos += r.avisos
            metodo = MetodoExtraccion.PARSER_SERCOP
        }

        val materiales = items.filter { esMaterial(it) }
        val bajas = materiales.count { it.confianza == Confianza.BAJA }
        val necesitaIa = forzarIa || formato == FormatoOrden.DESCONOCIDO || materiales.isEmpty() || bajas * 5 > materiales.size
        if (necesitaIa) {
            if (!cliente.disponible) {
                avisos += if (materiales.isEmpty()) {
                    "No se encontraron materiales y la extracción con IA está deshabilitada: agrégalos manualmente"
                } else {
                    "La extracción con IA está deshabilitada: se usó solo el parser"
                }
            } else {
                try {
                    val deIa = ia.extraer(doc, pdf, permitirPdf)
                    items = combinar(items, deIa)
                    metodo = if (metodo == MetodoExtraccion.PARSER_SERCOP) MetodoExtraccion.PARSER_SERCOP_IA else MetodoExtraccion.IA
                } catch (e: ExtraccionIaException) {
                    avisos += "Extracción con IA no disponible: ${e.message}"
                }
            }
        }
        verificar(items, doc)
        return Analisis(formato, cabecera, items, metodo, avisos)
    }

    /** Une parser e IA sin duplicar y marca las diferencias para que el admin las revise. */
    fun combinar(parser: List<ItemPropuesto>, deIa: List<ItemPropuesto>): List<ItemPropuesto> {
        if (deIa.isEmpty()) return parser
        val porClave = parser.associateBy { sercop.clave(it.nombre) }
        val clavesIa = deIa.map { sercop.clave(it.nombre) }.toSet()
        val nuevos = deIa.filter { i ->
            val previo = porClave[sercop.clave(i.nombre)]
            if (previo != null) {
                if (previo.abastece != i.abastece && i.abastece == Abastece.ENTIDAD_CONTRATANTE) {
                    previo.bajarA(Confianza.MEDIA, "la IA indica que lo provee la entidad contratante")
                }
                false
            } else {
                true
            }
        }.distinctBy { sercop.clave(it.nombre) }
        parser.filter { esMaterial(it) && sercop.clave(it.nombre) !in clavesIa }.forEach { it.motivos += "la IA no lo detectó" }
        nuevos.forEach { it.bajarA(Confianza.MEDIA, "solo lo detectó la IA") }
        return parser + nuevos
    }

    /** Anti-alucinación: el texto original de cada ítem debe aparecer en el PDF; si no, BAJA y no seleccionado. */
    fun verificar(items: List<ItemPropuesto>, doc: DocumentoPdf) {
        items.forEach { item ->
            when {
                !doc.tieneTexto -> item.bajarA(Confianza.BAJA, "PDF sin capa de texto: no se pudo verificar")
                item.textoOriginal.isNullOrBlank() ||
                    DocumentoPdf.normalizarParaVerificar(item.textoOriginal) !in doc.textoNormalizado ->
                    item.bajarA(Confianza.BAJA, "no verificado en el texto")
            }
        }
    }

    private fun esMaterial(i: ItemPropuesto) = i.seccion == SeccionOrigen.MATERIALES || i.seccion == SeccionOrigen.CLAUSULA_ENTIDAD
}
