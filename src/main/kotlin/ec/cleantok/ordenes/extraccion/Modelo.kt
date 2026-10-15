package ec.cleantok.ordenes.extraccion

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import java.math.BigDecimal
import java.time.LocalDate

enum class SeccionOrigen { MATERIALES, HERRAMIENTAS, MAQUINARIA, EQUIPAMIENTO, CLAUSULA_ENTIDAD, MANUAL }

enum class OrigenMaterial { PARSER, IA, MANUAL }

enum class Confianza { ALTA, MEDIA, BAJA }

enum class FormatoOrden { SERCOP, DESCONOCIDO }

/** Propuesta de material antes de guardarse como `material_extraido`. */
class ItemPropuesto(
    /** Fragmento literal del PDF (espacios colapsados). */
    val textoOriginal: String?,
    var nombre: String,
    var categoria: Categoria,
    var unidad: Unidad,
    var presentacion: String?,
    val seccion: SeccionOrigen,
    var abastece: Abastece,
    val esConsumible: Boolean,
    val pagina: Int?,
    val origen: OrigenMaterial,
    var confianza: Confianza = Confianza.ALTA,
    var seleccionado: Boolean = true,
    val motivos: MutableList<String> = mutableListOf(),
    /** Índice (en la lista final) del ítem con el que se propone fusionar. */
    var duplicadoDe: Int? = null,
) {
    fun bajarA(c: Confianza, motivo: String) {
        if (c.ordinal > confianza.ordinal) confianza = c
        if (motivo !in motivos) motivos += motivo
        if (confianza == Confianza.BAJA) seleccionado = false
    }

    override fun toString() = "$seccion:$nombre"
}

data class Cabecera(
    val numeroOrden: String? = null,
    val entidadContratante: String? = null,
    val fechaEmision: LocalDate? = null,
    val areaM2: BigDecimal? = null,
    val descripcionServicio: String? = null,
    val vigenciaMeses: Int? = null,
)

data class ResultadoExtraccion(val cabecera: Cabecera, val items: List<ItemPropuesto>, val avisos: List<String>) {
    val materiales: List<ItemPropuesto> get() = items.filter { it.seccion == SeccionOrigen.MATERIALES || it.seccion == SeccionOrigen.CLAUSULA_ENTIDAD }
}
