package ec.cleantok.ordenes.servicio

import ec.cleantok.archivos.dominio.ArchivoRepository
import ec.cleantok.archivos.servicio.AlmacenamientoArchivos
import ec.cleantok.ordenes.dominio.EstadoMaterial
import ec.cleantok.ordenes.dominio.EstadoOrden
import ec.cleantok.ordenes.dominio.MaterialExtraido
import ec.cleantok.ordenes.dominio.MaterialExtraidoRepository
import ec.cleantok.ordenes.dominio.OrdenServicioRepository
import ec.cleantok.ordenes.extraccion.LectorPdf
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executor

/** Se publica al subir una orden (o al reintentar con IA); se procesa al confirmar la transacción. */
data class OrdenPorProcesar(val ordenId: UUID)

@Configuration
@EnableAsync
class ConfiguracionAsincrona {
    @Bean("ordenesExecutor")
    fun ordenesExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        queueCapacity = 100
        setThreadNamePrefix("ordenes-")
        initialize()
    }
}

@Service
class ProcesadorOrdenes(
    private val ordenes: OrdenServicioRepository,
    private val materiales: MaterialExtraidoRepository,
    private val archivos: ArchivoRepository,
    private val almacen: AlmacenamientoArchivos,
    private val analizador: AnalizadorOrdenes,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    @Async("ordenesExecutor")
    fun procesarAsync(ordenId: UUID) = procesar(ordenId)

    /** Lee el PDF, extrae, verifica y deja la orden en PENDIENTE_REVISION (o ERROR si no hay nada). */
    fun procesar(ordenId: UUID) {
        val (ruta, permitirPdf, forzarIa) = tx.execute {
            val o = ordenes.findById(ordenId).orElse(null) ?: return@execute null
            if (o.estado != EstadoOrden.PROCESANDO) return@execute null
            Triple(archivos.findById(o.archivoId).get().ruta, o.permitirEnvioPdfIa, o.forzarIa)
        } ?: return
        try {
            val bytes = almacen.leer(ruta)
            val doc = LectorPdf.leer(bytes)
            val an = analizador.analizar(doc, bytes, permitirPdf, forzarIa)
            tx.executeWithoutResult {
                val o = ordenes.bloquear(ordenId) ?: return@executeWithoutResult
                if (o.estado != EstadoOrden.PROCESANDO) return@executeWithoutResult
                materiales.deleteAllByOrdenServicioId(o.id)
                materiales.flush()
                val guardados = an.items.mapIndexed { i, it ->
                    materiales.save(
                        MaterialExtraido(
                            empresaId = o.empresaId,
                            ordenServicioId = o.id,
                            posicion = i + 1,
                            textoOriginal = it.textoOriginal?.take(2000),
                            nombre = it.nombre.take(200),
                            categoria = it.categoria,
                            unidad = it.unidad,
                            presentacion = it.presentacion?.take(100),
                            seccion = it.seccion,
                            abastece = it.abastece,
                            esConsumible = it.esConsumible,
                            pagina = it.pagina,
                            origen = it.origen,
                            confianza = it.confianza,
                            motivoRevision = it.motivos.joinToString("; ").take(500).ifBlank { null },
                            seleccionadoPorDefecto = it.seleccionado,
                        ),
                    )
                }
                an.items.forEachIndexed { i, it -> it.duplicadoDe?.let { d -> guardados[i].fusionarConId = guardados[d].id } }
                o.formatoDetectado = an.formato
                o.metodoExtraccion = an.metodo
                an.cabecera.let { c ->
                    o.numeroOrden = c.numeroOrden ?: o.numeroOrden
                    o.entidadContratante = c.entidadContratante?.take(300) ?: o.entidadContratante
                    o.fechaEmision = c.fechaEmision ?: o.fechaEmision
                    o.areaM2 = c.areaM2 ?: o.areaM2
                    o.descripcionServicio = c.descripcionServicio?.take(1000) ?: o.descripcionServicio
                    o.vigenciaMeses = c.vigenciaMeses ?: o.vigenciaMeses
                }
                o.textoExtraido = doc.texto
                o.tieneCapaTexto = doc.tieneTexto
                o.avisos = an.avisos
                o.forzarIa = false
                if (guardados.none { it.estado != EstadoMaterial.DESCARTADO }) {
                    o.estado = EstadoOrden.ERROR
                    o.errorDetalle = "No se encontraron materiales en el documento. Puedes agregarlos manualmente o reintentar con IA."
                } else {
                    o.estado = EstadoOrden.PENDIENTE_REVISION
                    o.errorDetalle = null
                }
            }
        } catch (e: Exception) {
            // Sin contenido del PDF en el log: solo la orden y el tipo de error.
            log.error("Error procesando la orden {}: {}", ordenId, e.javaClass.name, e)
            tx.executeWithoutResult {
                ordenes.findById(ordenId).ifPresent { o ->
                    o.estado = EstadoOrden.ERROR
                    o.forzarIa = false
                    o.errorDetalle = "No se pudo procesar el PDF. Puedes agregar los materiales manualmente."
                }
            }
        }
    }
}

@Component
class DisparadorOrdenes(
    private val procesador: ProcesadorOrdenes,
    private val ordenes: OrdenServicioRepository,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun alSubir(e: OrdenPorProcesar) = procesador.procesarAsync(e.ordenId)

    /** Si la API se reinició a mitad de un procesamiento, se retoma. */
    @EventListener(ApplicationReadyEvent::class)
    fun retomarPendientes() = ordenes.findAllByEstado(EstadoOrden.PROCESANDO).forEach { procesador.procesarAsync(it.id) }
}
