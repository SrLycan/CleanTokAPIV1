package ec.cleantok.archivos.servicio

import ec.cleantok.archivos.dominio.Archivo
import ec.cleantok.archivos.dominio.ArchivoRepository
import ec.cleantok.archivos.dominio.TipoArchivo
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

@Service
class ArchivoService(
    private val archivos: ArchivoRepository,
    private val almacen: AlmacenamientoArchivos,
    private val clock: Clock,
) {
    /**
     * Guarda el binario y su registro. Si la transacción que lo envuelve se revierte, se borra el binario
     * para no dejar huérfanos.
     */
    fun guardar(tipo: TipoArchivo, contenido: ByteArray, mime: String, extension: String, nombreOriginal: String?): Archivo {
        val usuario = ContextoSeguridad.usuario()
        val empresaId = ContextoSeguridad.empresaId()
        val hoy = clock.instant().atOffset(ZoneOffset.UTC)
        val ruta = "%s/%s/%04d/%02d/%s.%s".format(empresaId, tipo.name.lowercase(), hoy.year, hoy.monthValue, UUID.randomUUID(), extension)
        almacen.guardar(ruta, contenido, mime)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) runCatching { almacen.eliminar(ruta) }
                }
            })
        }
        return archivos.save(
            Archivo(
                empresaId = empresaId,
                tipo = tipo,
                ruta = ruta,
                mime = mime,
                tamano = contenido.size.toLong(),
                sha256 = sha256(contenido),
                nombreOriginal = nombreOriginal?.take(255),
                subidoPor = usuario.id,
            ),
        )
    }

    fun deMiEmpresa(id: UUID): Archivo =
        archivos.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Archivo")

    fun contenido(a: Archivo): ByteArray = almacen.leer(a.ruta)

    /** Valida que un fotoId recibido sea una FOTO de la empresa del usuario. */
    fun exigirFoto(fotoId: UUID?) {
        if (fotoId == null) return
        val a = archivos.findByIdAndEmpresaId(fotoId, ContextoSeguridad.empresaId())
        if (a == null || a.tipo != TipoArchivo.FOTO) throw ValidacionException("La foto indicada no existe; súbela primero con POST /fotos")
    }

    companion object {
        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
