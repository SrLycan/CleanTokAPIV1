package ec.cleantok.ordenes.dominio

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.entidad.EntidadBase
import ec.cleantok.ordenes.extraccion.Confianza
import ec.cleantok.ordenes.extraccion.FormatoOrden
import ec.cleantok.ordenes.extraccion.OrigenMaterial
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class EstadoOrden { PROCESANDO, PENDIENTE_REVISION, CONFIRMADA, ERROR }

enum class MetodoExtraccion(val valor: String) {
    PARSER_SERCOP("PARSER_SERCOP"),
    IA("IA"),
    PARSER_SERCOP_IA("PARSER_SERCOP+IA"),
    MANUAL("MANUAL"),
}

@Converter(autoApply = false)
class MetodoExtraccionConverter : AttributeConverter<MetodoExtraccion?, String?> {
    override fun convertToDatabaseColumn(a: MetodoExtraccion?): String? = a?.valor
    override fun convertToEntityAttribute(d: String?): MetodoExtraccion? = d?.let { v -> MetodoExtraccion.entries.first { it.valor == v } }
}

enum class EstadoMaterial { PENDIENTE, ACEPTADO, EDITADO, DESCARTADO }

@Entity
@Table(name = "orden_servicio")
class OrdenServicio(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(name = "archivo_id", nullable = false, updatable = false) val archivoId: UUID,
    @Column(nullable = false, updatable = false) val sha256: String,
    @Column(name = "subido_por", nullable = false, updatable = false) val subidoPor: UUID,
    @Column(name = "permitir_envio_pdf_ia", nullable = false) var permitirEnvioPdfIa: Boolean,
) : EntidadBase() {
    @Column(name = "numero_orden") var numeroOrden: String? = null
    @Column(name = "entidad_contratante") var entidadContratante: String? = null
    @Column(name = "fecha_emision") var fechaEmision: LocalDate? = null
    @Column(name = "area_m2") var areaM2: BigDecimal? = null
    @Column(name = "descripcion_servicio") var descripcionServicio: String? = null
    @Column(name = "vigencia_meses") var vigenciaMeses: Int? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "formato_detectado")
    var formatoDetectado: FormatoOrden? = null

    @Convert(converter = MetodoExtraccionConverter::class)
    @Column(name = "metodo_extraccion")
    var metodoExtraccion: MetodoExtraccion? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoOrden = EstadoOrden.PROCESANDO

    @Column(name = "texto_extraido") var textoExtraido: String? = null
    @Column(name = "error_detalle") var errorDetalle: String? = null
    @Column(name = "avisos") var avisosTexto: String? = null
    @Column(name = "tiene_capa_texto") var tieneCapaTexto: Boolean? = null
    @Column(name = "forzar_ia", nullable = false) var forzarIa: Boolean = false
    @Column(name = "confirmada_por") var confirmadaPor: UUID? = null
    @Column(name = "confirmada_en") var confirmadaEn: Instant? = null

    var avisos: List<String>
        get() = avisosTexto?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) { avisosTexto = v.distinct().joinToString("\n").take(4000).ifBlank { null } }
}

@Entity
@Table(name = "material_extraido")
class MaterialExtraido(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "orden_servicio_id", nullable = false, updatable = false) val ordenServicioId: UUID,
    @Column(nullable = false) var posicion: Int,
    @Column(name = "texto_original", updatable = false) val textoOriginal: String?,
    @Column(name = "nombre_sugerido", nullable = false) var nombre: String,
    @Enumerated(EnumType.STRING) @Column(name = "categoria_sugerida", nullable = false) var categoria: Categoria,
    @Enumerated(EnumType.STRING) @Column(name = "unidad_sugerida", nullable = false) var unidad: Unidad,
    var presentacion: String?,
    @Enumerated(EnumType.STRING) @Column(name = "seccion_origen", nullable = false, updatable = false) val seccion: SeccionOrigen,
    @Enumerated(EnumType.STRING) @Column(name = "abastece_sugerido", nullable = false) var abastece: Abastece,
    @Column(name = "es_consumible_sugerido", nullable = false) var esConsumible: Boolean,
    val pagina: Int?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val origen: OrigenMaterial,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var confianza: Confianza,
    @Column(name = "motivo_revision") var motivoRevision: String?,
    @Column(name = "seleccionado_por_defecto", nullable = false) val seleccionadoPorDefecto: Boolean,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoMaterial = EstadoMaterial.PENDIENTE

    @Column(name = "fusionar_con_id") var fusionarConId: UUID? = null
    @Column(name = "producto_id") var productoId: UUID? = null

    /** Se convierte en producto al confirmar: aceptado, editado, o pendiente que venía seleccionado. */
    val seleccionado: Boolean
        get() = estado == EstadoMaterial.ACEPTADO || estado == EstadoMaterial.EDITADO ||
            (estado == EstadoMaterial.PENDIENTE && seleccionadoPorDefecto)
}

interface OrdenServicioRepository : JpaRepository<OrdenServicio, UUID> {
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): OrdenServicio?

    fun findByEstablecimientoIdAndSha256(establecimientoId: UUID, sha256: String): OrdenServicio?

    fun findAllByEstablecimientoIdOrderByCreatedAtDesc(establecimientoId: UUID, pageable: Pageable): Page<OrdenServicio>

    fun findAllByEstado(estado: EstadoOrden): List<OrdenServicio>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrdenServicio o where o.id = :id")
    fun bloquear(id: UUID): OrdenServicio?
}

interface MaterialExtraidoRepository : JpaRepository<MaterialExtraido, UUID> {
    fun findAllByOrdenServicioIdOrderByPosicion(ordenServicioId: UUID): List<MaterialExtraido>

    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): MaterialExtraido?

    fun deleteAllByOrdenServicioId(ordenServicioId: UUID)

    @Query("select coalesce(max(m.posicion), 0) from MaterialExtraido m where m.ordenServicioId = :ordenId")
    fun maxPosicion(ordenId: UUID): Int
}
