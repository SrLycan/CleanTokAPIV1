package ec.cleantok.monitoreo.dominio

import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class TipoAlerta { STOCK_BAJO, SIN_STOCK, NIVEL_CRITICO, COBERTURA_BAJA }

enum class Severidad { MEDIA, ALTA }

enum class EstadoAlerta { ABIERTA, ATENDIDA }

enum class CierreAlerta { AUTOMATICO, MANUAL }

/** Gancho para la verificación del nivel por foto con IA (no implementada en esta fase). */
enum class VerificacionIa { NO_APLICA, PENDIENTE, COINCIDE, DIFIERE, ERROR }

@Entity
@Table(name = "reporte_nivel")
class ReporteNivel(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(name = "client_uuid", nullable = false, updatable = false) val clientUuid: UUID,
    @Column(name = "producto_id", nullable = false, updatable = false) val productoId: UUID,
    @Column(name = "ubicacion_id", nullable = false, updatable = false) val ubicacionId: UUID,
    @Column(name = "nivel_pct", nullable = false, updatable = false) val nivelPct: Int,
    @Column(name = "foto_id", updatable = false) val fotoId: UUID?,
    @Column(name = "usuario_id", nullable = false, updatable = false) val usuarioId: UUID,
    @Column(name = "ocurrido_en", nullable = false, updatable = false) val ocurridoEn: Instant,
    @Column(name = "registrado_en", nullable = false, updatable = false) val registradoEn: Instant,
    @Column(updatable = false) val nota: String?,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(name = "verificacion_ia", nullable = false)
    var verificacionIa: VerificacionIa = VerificacionIa.NO_APLICA

    @Column(name = "nivel_ia")
    var nivelIa: Int? = null
}

@Entity
@Table(name = "alerta")
class Alerta(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    @Column(name = "producto_id", nullable = false, updatable = false) val productoId: UUID,
    @Column(name = "ubicacion_id", updatable = false) val ubicacionId: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val tipo: TipoAlerta,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var severidad: Severidad,
    @Column(nullable = false) var mensaje: String,
    @Column(name = "creada_en", nullable = false, updatable = false) val creadaEn: Instant,
) : EntidadBase() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoAlerta = EstadoAlerta.ABIERTA
        protected set

    @Column(name = "atendida_por")
    var atendidaPor: UUID? = null
        protected set

    @Column(name = "atendida_en")
    var atendidaEn: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "cierre")
    var cierre: CierreAlerta? = null
        protected set

    @Column(name = "nota_atencion")
    var notaAtencion: String? = null
        protected set

    @Column(nullable = false)
    var rearmada: Boolean = false

    val abierta: Boolean get() = estado == EstadoAlerta.ABIERTA

    fun cerrar(cierre: CierreAlerta, ahora: Instant, usuarioId: UUID? = null, nota: String? = null) {
        if (!abierta) return
        estado = EstadoAlerta.ATENDIDA
        this.cierre = cierre
        atendidaEn = ahora
        atendidaPor = usuarioId
        notaAtencion = nota
    }
}
