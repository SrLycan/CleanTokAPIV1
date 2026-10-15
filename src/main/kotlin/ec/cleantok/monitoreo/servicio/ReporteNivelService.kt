package ec.cleantok.monitoreo.servicio

import ec.cleantok.archivos.servicio.ArchivoService
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Zona
import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.identidad.dominio.UsuarioRepository
import ec.cleantok.inventario.api.AlertaResumenDto
import ec.cleantok.inventario.api.ProductoRefDto
import ec.cleantok.inventario.api.RefDto
import ec.cleantok.inventario.api.ReporteNivelRequest
import ec.cleantok.inventario.api.UbicacionRefDto
import ec.cleantok.inventario.servicio.ProcesadorReporteNivel
import ec.cleantok.monitoreo.api.ReporteNivelDto
import ec.cleantok.monitoreo.api.ReporteNivelResultadoDto
import ec.cleantok.monitoreo.dominio.ReporteNivel
import ec.cleantok.monitoreo.dominio.ReporteNivelRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class ReporteNivelService(
    private val reportes: ReporteNivelRepository,
    private val productos: ProductoRepository,
    private val ubicaciones: UbicacionRepository,
    private val usuarios: UsuarioRepository,
    private val acceso: AccesoEstablecimiento,
    private val archivos: ArchivoService,
    private val motor: MotorAlertas,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : ProcesadorReporteNivel {
    private val tx = TransactionTemplate(transactionManager)
    private val txLectura = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /** Idempotente por clientUuid, igual que los movimientos. */
    fun registrar(req: ReporteNivelRequest): ReporteNivelResultadoDto {
        val usuario = ContextoSeguridad.usuario()
        txLectura.execute { existente(req.clientUuid!!, usuario) }?.let { return it }
        return try {
            tx.execute { aplicar(req, usuario) }!!
        } catch (e: DataIntegrityViolationException) {
            txLectura.execute { existente(req.clientUuid!!, usuario) } ?: throw e
        }
    }

    override fun procesar(req: ReporteNivelRequest): Pair<UUID, Boolean> = registrar(req).let { it.reporte.id to it.duplicado }

    private fun existente(clientUuid: UUID, usuario: UsuarioAutenticado): ReporteNivelResultadoDto? {
        val r = reportes.findByClientUuid(clientUuid) ?: return null
        if (r.empresaId != usuario.empresaId) throw ConflictoException("El clientUuid ya fue usado")
        return ReporteNivelResultadoDto(dto(r), duplicado = true, alertas = emptyList())
    }

    private fun aplicar(req: ReporteNivelRequest, usuario: UsuarioAutenticado): ReporteNivelResultadoDto {
        val nivel = req.nivelPct!!
        if (nivel !in NIVELES) throw ValidacionException("El nivel debe ser 0, 25, 50, 75 o 100")
        val u = acceso.ubicacionVisible(req.ubicacionId!!)
        if (u.esBodega) throw ValidacionException("El nivel se reporta en los pisos, no en la bodega")
        if (!u.activo) throw ValidacionException("La ubicación está desactivada")
        val p = productos.findByIdAndEmpresaId(req.productoId!!, usuario.empresaId!!)
            ?.takeIf { it.establecimientoId == u.establecimientoId } ?: throw NoEncontradoException("Producto")
        if (!p.activo) throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, CodigoError.PRODUCTO_INACTIVO, "El producto \"${p.nombre}\" está desactivado")
        val ahora = clock.instant()
        val ocurrido = req.ocurridoEn!!.toInstant()
        if (ocurrido.isAfter(ahora.plus(Duration.ofMinutes(10)))) {
            throw ValidacionException("La fecha del reporte está en el futuro; revisa la hora del dispositivo")
        }
        archivos.exigirFoto(req.fotoId)
        val r = reportes.saveAndFlush(
            ReporteNivel(
                empresaId = p.empresaId,
                establecimientoId = p.establecimientoId,
                clientUuid = req.clientUuid!!,
                productoId = p.id,
                ubicacionId = u.id,
                nivelPct = nivel,
                fotoId = req.fotoId,
                usuarioId = usuario.id,
                ocurridoEn = ocurrido,
                registradoEn = ahora,
                nota = req.nota?.trim()?.ifBlank { null },
            ),
        )
        val alertas: List<AlertaResumenDto> = motor.evaluar(p, ahora).map(AlertaService::resumen)
        return ReporteNivelResultadoDto(dto(r), duplicado = false, alertas = alertas)
    }

    private fun dto(r: ReporteNivel): ReporteNivelDto {
        val p = productos.findById(r.productoId).get()
        val u = ubicaciones.findById(r.ubicacionId).get()
        val us = usuarios.findById(r.usuarioId).get()
        return ReporteNivelDto(
            r.id, r.clientUuid, ProductoRefDto(p.id, p.nombre, p.unidad), UbicacionRefDto(u.id, u.nombre, u.tipo),
            r.nivelPct, r.fotoId, RefDto(us.id, us.nombre), Zona.presentar(r.ocurridoEn), Zona.presentar(r.registradoEn),
            r.nota, r.verificacionIa, r.nivelIa,
        )
    }

    companion object {
        val NIVELES = setOf(0, 25, 50, 75, 100)
    }
}
