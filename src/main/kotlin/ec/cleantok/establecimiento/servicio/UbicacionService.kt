package ec.cleantok.establecimiento.servicio

import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.api.ActualizarUbicacionRequest
import ec.cleantok.establecimiento.api.CrearUbicacionRequest
import ec.cleantok.establecimiento.api.UbicacionDto
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.Ubicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Token opaco del QR: 18 bytes aleatorios en base64url = 24 caracteres, sin ids ni datos. */
@Component
class GeneradorTokenQr {
    private val random = SecureRandom()

    fun nuevo(): String = ByteArray(18).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

@Service
class UbicacionService(
    private val ubicaciones: UbicacionRepository,
    private val acceso: AccesoEstablecimiento,
    private val tokens: GeneradorTokenQr,
    private val props: CleanTokProperties,
) {
    fun urlQr(token: String): String = props.urlPublica.trimEnd('/') + "/q/" + token

    /** El admin ve todas (con la URL del QR); el encargado solo las activas y sin URL. */
    @Transactional(readOnly = true)
    fun listar(establecimientoId: UUID): List<UbicacionDto> {
        val e = acceso.visible(establecimientoId)
        val admin = ContextoSeguridad.usuario().gestionaSede
        return ubicaciones.findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(e.id)
            .filter { admin || it.activo }
            .map { dto(it, admin) }
    }

    @Transactional
    fun crear(establecimientoId: UUID, req: CrearUbicacionRequest): UbicacionDto {
        val e = acceso.deMiEmpresa(establecimientoId)
        exigirNoBodega(req.tipo)
        val u = ubicaciones.save(
            Ubicacion(
                empresaId = e.empresaId,
                establecimientoId = e.id,
                nombre = req.nombre.trim(),
                tipo = req.tipo,
                orden = req.orden ?: (ubicaciones.maxOrden(e.id) + 1),
                qrToken = tokens.nuevo(),
            ),
        )
        return dto(u, true)
    }

    @Transactional
    fun actualizar(id: UUID, req: ActualizarUbicacionRequest): UbicacionDto {
        val u = acceso.ubicacionDeMiEmpresa(id)
        u.nombre = req.nombre.trim()
        if (req.tipo != null && req.tipo != u.tipo) {
            if (u.esBodega) throw ValidacionException("La bodega no puede cambiar de tipo")
            exigirNoBodega(req.tipo)
            u.tipo = req.tipo
        }
        return dto(u, true)
    }

    @Transactional
    fun cambiarActivo(id: UUID, activo: Boolean): UbicacionDto {
        val u = acceso.ubicacionDeMiEmpresa(id)
        if (u.esBodega && !activo) throw ValidacionException("La bodega no se puede desactivar")
        u.activo = activo
        return dto(u, true)
    }

    /** Asigna orden 1..n según la lista recibida. */
    @Transactional
    fun ordenar(establecimientoId: UUID, ids: List<UUID>): List<UbicacionDto> {
        val e = acceso.deMiEmpresa(establecimientoId)
        val todas = ubicaciones.findAllByEstablecimientoIdOrderByOrdenAscNombreAsc(e.id).associateBy { it.id }
        val ajenas = ids.filter { it !in todas }
        if (ajenas.isNotEmpty()) throw ValidacionException("Hay ubicaciones que no son de este establecimiento", mapOf("ubicacionIds" to ajenas))
        ids.distinct().forEachIndexed { i, id -> todas.getValue(id).orden = i + 1 }
        return listar(establecimientoId)
    }

    /** Invalida el QR impreso: el token anterior deja de funcionar. */
    @Transactional
    fun regenerarQr(id: UUID): UbicacionDto {
        val u = acceso.ubicacionDeMiEmpresa(id)
        u.qrToken = tokens.nuevo()
        return dto(u, true)
    }

    private fun dto(u: Ubicacion, conUrl: Boolean) = UbicacionDto.de(u, if (conUrl) urlQr(u.qrToken) else null)

    private fun exigirNoBodega(tipo: TipoUbicacion) {
        if (tipo == TipoUbicacion.BODEGA) throw ValidacionException("Cada establecimiento ya tiene su bodega; usa PISO o AREA")
    }
}
