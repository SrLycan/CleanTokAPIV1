package ec.cleantok.archivos.servicio

import ec.cleantok.comun.config.CleanTokProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * Almacén de binarios. La base solo guarda la ruta relativa: cambiar de disco local a la nube
 * no requiere migrar la tabla `archivo`.
 */
interface AlmacenamientoArchivos {
    /** Guarda [contenido] en [ruta] (relativa, con `/`). */
    fun guardar(ruta: String, contenido: ByteArray, mime: String)

    fun leer(ruta: String): ByteArray

    fun eliminar(ruta: String)
}

/** Disco local bajo `ALMACENAMIENTO_RUTA` (por defecto). */
@Component
@ConditionalOnProperty(prefix = "cleantok.almacenamiento", name = ["tipo"], havingValue = "local", matchIfMissing = true)
class AlmacenamientoLocal(props: CleanTokProperties) : AlmacenamientoArchivos {
    private val raiz: Path = Path.of(props.almacenamiento.ruta).toAbsolutePath().normalize()

    override fun guardar(ruta: String, contenido: ByteArray, mime: String) {
        val destino = resolver(ruta)
        Files.createDirectories(destino.parent)
        Files.write(destino, contenido, StandardOpenOption.CREATE_NEW)
    }

    override fun leer(ruta: String): ByteArray = Files.readAllBytes(resolver(ruta))

    override fun eliminar(ruta: String) {
        Files.deleteIfExists(resolver(ruta))
    }

    /** Impide escapar de la raíz con `..`. */
    private fun resolver(ruta: String): Path {
        val p = raiz.resolve(ruta).normalize()
        require(p.startsWith(raiz)) { "Ruta fuera del almacenamiento" }
        return p
    }
}

/**
 * Supabase Storage por su API REST (sin dependencias extra). Se activa con `ALMACENAMIENTO_TIPO=supabase`,
 * `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` y `SUPABASE_BUCKET` (bucket privado).
 */
@Component
@ConditionalOnProperty(prefix = "cleantok.almacenamiento", name = ["tipo"], havingValue = "supabase")
class AlmacenamientoSupabase(props: CleanTokProperties) : AlmacenamientoArchivos {
    private val cfg = props.almacenamiento.supabase
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    init {
        require(cfg.url.isNotBlank() && cfg.serviceKey.isNotBlank() && cfg.bucket.isNotBlank()) {
            "Faltan SUPABASE_URL, SUPABASE_SERVICE_KEY o SUPABASE_BUCKET"
        }
    }

    override fun guardar(ruta: String, contenido: ByteArray, mime: String) {
        val r = enviar(HttpRequest.newBuilder(uri(ruta)).header("Content-Type", mime).POST(HttpRequest.BodyPublishers.ofByteArray(contenido)))
        check(r.statusCode() in 200..299) { "Supabase Storage respondió ${r.statusCode()} al guardar" }
    }

    override fun leer(ruta: String): ByteArray {
        val r = enviar(HttpRequest.newBuilder(uri(ruta)).GET())
        check(r.statusCode() == 200) { "Supabase Storage respondió ${r.statusCode()} al leer" }
        return r.body()
    }

    override fun eliminar(ruta: String) {
        enviar(HttpRequest.newBuilder(uri(ruta)).DELETE())
    }

    private fun uri(ruta: String): URI {
        val camino = ruta.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8) }
        return URI.create("${cfg.url.trimEnd('/')}/storage/v1/object/${cfg.bucket}/$camino")
    }

    private fun enviar(b: HttpRequest.Builder): HttpResponse<ByteArray> =
        http.send(
            b.header("Authorization", "Bearer ${cfg.serviceKey}").header("apikey", cfg.serviceKey).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
}
