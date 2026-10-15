package ec.cleantok.monitoreo.servicio

import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.api.AlertaEnQrDto
import ec.cleantok.establecimiento.api.QrResolucionDto
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.establecimiento.servicio.ContenidoQr
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.api.AlertaResumenDto
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.api.UbicacionRefDto
import ec.cleantok.inventario.dominio.Movimiento
import ec.cleantok.inventario.servicio.ReaccionMovimiento
import ec.cleantok.monitoreo.api.AlertaDto
import ec.cleantok.monitoreo.dominio.Alerta
import ec.cleantok.monitoreo.dominio.AlertaRepository
import ec.cleantok.monitoreo.dominio.CierreAlerta
import ec.cleantok.monitoreo.dominio.EstadoAlerta
import ec.cleantok.monitoreo.dominio.TipoAlerta
import jakarta.persistence.criteria.Predicate
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

@Service
class AlertaService(
    private val alertas: AlertaRepository,
    private val productos: ProductoRepository,
    private val ubicaciones: UbicacionRepository,
    private val usuarios: UsuarioRepository,
    private val acceso: AccesoEstablecimiento,
    private val motor: MotorAlertas,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun listar(establecimientoId: UUID, estado: EstadoAlerta?, tipo: TipoAlerta?, pageable: Pageable): Page<AlertaDto> {
        val e = acceso.visible(establecimientoId)
        val spec = Specification<Alerta> { root, _, cb ->
            val f = mutableListOf<Predicate>(cb.equal(root.get<UUID>("establecimientoId"), e.id))
            estado?.let { f += cb.equal(root.get<EstadoAlerta>("estado"), it) }
            tipo?.let { f += cb.equal(root.get<TipoAlerta>("tipo"), it) }
            cb.and(*f.toTypedArray())
        }
        val pagina = alertas.findAll(spec, pageable)
        return PageImpl(dtos(pagina.content), pagina.pageable, pagina.totalElements)
    }

    /** Marca la alerta como atendida (admin o encargado asignado). Si ya estaba cerrada, la devuelve igual. */
    @Transactional
    fun atender(id: UUID, nota: String?): AlertaDto {
        val a = alertas.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Alerta")
        acceso.visible(a.establecimientoId)
        a.cerrar(CierreAlerta.MANUAL, clock.instant(), ContextoSeguridad.usuario().id, nota?.trim()?.ifBlank { null })
        return dtos(listOf(a)).single()
    }

    /** Reevalúa todas las reglas del establecimiento ahora mismo (útil en la demo). */
    @Transactional
    fun reevaluar(establecimientoId: UUID): List<AlertaDto> {
        val e = acceso.deMiEmpresa(establecimientoId)
        val ahora = clock.instant()
        productos.findAllByEstablecimientoIdAndActivoTrueOrderByNombre(e.id).forEach { motor.evaluar(it, ahora) }
        return dtos(alertas.findAll(Specification { root, _, cb ->
            cb.and(cb.equal(root.get<UUID>("establecimientoId"), e.id), cb.equal(root.get<EstadoAlerta>("estado"), EstadoAlerta.ABIERTA))
        }))
    }

    fun dtos(lista: List<Alerta>): List<AlertaDto> {
        if (lista.isEmpty()) return emptyList()
        val prods = productos.findAllById(lista.map { it.productoId }.toSet()).associateBy { it.id }
        val ubics = ubicaciones.findAllById(lista.mapNotNull { it.ubicacionId }.toSet()).associateBy { it.id }
        val usrs = usuarios.findAllById(lista.mapNotNull { it.atendidaPor }.toSet()).associateBy { it.id }
        return lista.map { a ->
            val p = prods.getValue(a.productoId)
            AlertaDto(
                id = a.id,
                tipo = a.tipo,
                severidad = a.severidad,
                estado = a.estado,
                mensaje = a.mensaje,
                producto = ProductoRefDto(p.id, p.nombre, p.unidad),
                ubicacion = a.ubicacionId?.let { ubics[it] }?.let { UbicacionRefDto(it.id, it.nombre, it.tipo) },
                abastece = p.abastece,
                quienRepone = MotorAlertas.quienRepone(p.abastece),
                creadaEn = Zona.presentar(a.creadaEn),
                atendidaEn = a.atendidaEn?.let(Zona::presentar),
                atendidaPor = a.atendidaPor?.let { usrs[it] }?.let { RefDto(it.id, it.nombre) },
                cierre = a.cierre,
                notaAtencion = a.notaAtencion,
            )
        }
    }

    companion object {
        fun resumen(a: Alerta) = AlertaResumenDto(a.id, a.tipo.name, a.severidad.name, a.mensaje, a.productoId, a.ubicacionId)
    }
}

/** Evalúa las alertas del producto dentro de la transacción de cada movimiento. */
@Component
class ReaccionAlertas(private val motor: MotorAlertas, private val clock: Clock) : ReaccionMovimiento {
    override fun alRegistrar(movimiento: Movimiento, producto: Producto): List<AlertaResumenDto> =
        motor.evaluar(producto, clock.instant()).map(AlertaService::resumen)
}

/** Agrega al QR las alertas abiertas de esa ubicación (y, en la bodega, las del establecimiento). */
@Component
@Order(2)
class ContenidoQrAlertas(private val alertas: AlertaRepository) : ContenidoQr {
    override fun completar(base: QrResolucionDto): QrResolucionDto {
        val deUbicacion = alertas.findAllByUbicacionIdAndEstadoOrderByCreadaEnDesc(base.ubicacion.id, EstadoAlerta.ABIERTA)
        val generales = if (base.ubicacion.id == base.bodega.id) {
            alertas.findAllByEstablecimientoIdAndUbicacionIdIsNullAndEstadoOrderByCreadaEnDesc(base.establecimiento.id, EstadoAlerta.ABIERTA)
        } else {
            emptyList()
        }
        return base.copy(
            alertas = (deUbicacion + generales).map { AlertaEnQrDto(it.id, it.tipo.name, it.severidad.name, it.mensaje, it.productoId) },
        )
    }
}

/** Reevalúa cada hora todos los productos activos (la cobertura cambia con el paso del tiempo). */
@Component
class JobAlertas(
    private val establecimientos: EstablecimientoRepository,
    private val productos: ProductoRepository,
    private val motor: MotorAlertas,
    private val clock: Clock,
    private val props: CleanTokProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    @Scheduled(cron = "\${cleantok.alertas.cron}", zone = "America/Guayaquil")
    fun ejecutarProgramado() {
        if (props.alertas.jobHabilitado) ejecutar()
    }

    /** Cada producto en su propia transacción: un error no detiene al resto. Devuelve cuántas alertas se abrieron. */
    fun ejecutar(): Int {
        val ahora = clock.instant()
        var abiertas = 0
        var errores = 0
        establecimientos.findAll().filter { it.activo }.forEach { e ->
            productos.findAllByEstablecimientoIdAndActivoTrueOrderByNombre(e.id).forEach { p ->
                try {
                    abiertas += tx.execute { motor.evaluar(productos.findById(p.id).get(), ahora).size } ?: 0
                } catch (ex: Exception) {
                    errores++
                    log.error("Error evaluando alertas del producto {}: {}", p.id, ex.javaClass.name, ex)
                }
            }
        }
        log.info("Job de alertas: {} alertas abiertas, {} errores", abiertas, errores)
        return abiertas
    }
}
