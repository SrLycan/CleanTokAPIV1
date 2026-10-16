package ec.cleantok.notificacion

import ec.cleantok.comun.Zona
import ec.cleantok.comun.entidad.EntidadBase
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Aviso para un usuario dentro de la app (la campana). La app los consulta cada pocos segundos y muestra
 * una notificación del sistema por cada uno nuevo. [referenciaId] apunta a lo que abre al tocarla.
 */
@Entity
@Table(name = "notificacion")
class Notificacion(
    @Column(name = "usuario_id", nullable = false, updatable = false) val usuarioId: UUID,
    @Column(nullable = false, updatable = false) val tipo: String,
    @Column(nullable = false, updatable = false) val titulo: String,
    @Column(nullable = false, updatable = false) val mensaje: String,
    @Column(name = "referencia_tipo", updatable = false) val referenciaTipo: String? = null,
    @Column(name = "referencia_id", updatable = false) val referenciaId: UUID? = null,
    @Column(name = "referencia_texto", updatable = false) val referenciaTexto: String? = null,
) : EntidadBase() {
    @Column(name = "leida_en")
    var leidaEn: Instant? = null
}

interface NotificacionRepository : JpaRepository<Notificacion, UUID> {
    @Query("select n from Notificacion n where n.usuarioId = :usuarioId order by n.createdAt desc")
    fun recientes(usuarioId: UUID, pagina: PageRequest): List<Notificacion>

    fun countByUsuarioIdAndLeidaEnIsNull(usuarioId: UUID): Long

    fun findByIdAndUsuarioId(id: UUID, usuarioId: UUID): Notificacion?

    @Modifying
    @Query("update Notificacion n set n.leidaEn = :ahora where n.usuarioId = :usuarioId and n.leidaEn is null")
    fun leerTodas(usuarioId: UUID, ahora: Instant): Int
}

object TipoNotificacion {
    /** Un proveedor envió (o actualizó) su cotización de una solicitud. */
    const val OFERTA_RECIBIDA = "OFERTA_RECIBIDA"
}

data class NotificacionDto(
    val id: UUID,
    val tipo: String,
    val titulo: String,
    val mensaje: String,
    @field:Schema(description = "SOLICITUD, ORDEN… lo que abre la notificación") val referenciaTipo: String?,
    val referenciaId: UUID?,
    @field:Schema(description = "Código de la referencia (SC-003…)") val referenciaTexto: String?,
    val leida: Boolean,
    val creadaEn: OffsetDateTime,
)

data class ResumenNotificacionesDto(val noLeidas: Long, val recientes: List<NotificacionDto>)

@Service
class NotificacionService(
    private val notificaciones: NotificacionRepository,
    private val clock: Clock,
) {
    /** Deja el mismo aviso a cada destinatario. Lo llaman los módulos de dominio dentro de su transacción. */
    fun avisar(
        usuarioIds: Collection<UUID>,
        tipo: String,
        titulo: String,
        mensaje: String,
        referenciaTipo: String? = null,
        referenciaId: UUID? = null,
        referenciaTexto: String? = null,
    ) {
        usuarioIds.toSet().forEach {
            notificaciones.save(Notificacion(it, tipo, titulo.take(200), mensaje.take(500), referenciaTipo, referenciaId, referenciaTexto?.take(60)))
        }
    }

    @Transactional(readOnly = true)
    fun mias(): ResumenNotificacionesDto {
        val id = ContextoSeguridad.usuario().id
        return ResumenNotificacionesDto(
            noLeidas = notificaciones.countByUsuarioIdAndLeidaEnIsNull(id),
            recientes = notificaciones.recientes(id, PageRequest.of(0, 50)).map(::dto),
        )
    }

    @Transactional
    fun leer(id: UUID): NotificacionDto {
        val n = notificaciones.findByIdAndUsuarioId(id, ContextoSeguridad.usuario().id) ?: throw NoEncontradoException("Notificación")
        if (n.leidaEn == null) n.leidaEn = clock.instant()
        return dto(n)
    }

    @Transactional
    fun leerTodas(): ResumenNotificacionesDto {
        notificaciones.leerTodas(ContextoSeguridad.usuario().id, clock.instant())
        return mias()
    }

    private fun dto(n: Notificacion) = NotificacionDto(
        n.id, n.tipo, n.titulo, n.mensaje, n.referenciaTipo, n.referenciaId, n.referenciaTexto, n.leidaEn != null, Zona.presentar(n.createdAt),
    )
}

@RestController
@RequestMapping("/api/v1/notificaciones")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Notificaciones")
class NotificacionController(private val servicio: NotificacionService) {

    @GetMapping
    @Operation(summary = "Mis notificaciones (las 50 más recientes) y cuántas no he leído")
    fun mias(): ResumenNotificacionesDto = servicio.mias()

    @PostMapping("/{id}/leer")
    @Operation(summary = "Marca una notificación como leída")
    fun leer(@PathVariable id: UUID): NotificacionDto = servicio.leer(id)

    @PostMapping("/leer-todas")
    @Operation(summary = "Marca todas mis notificaciones como leídas")
    fun leerTodas(): ResumenNotificacionesDto = servicio.leerTodas()
}
