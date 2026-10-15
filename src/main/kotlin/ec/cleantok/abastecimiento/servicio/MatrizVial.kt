package ec.cleantok.abastecimiento.servicio

import ec.cleantok.comun.config.CleanTokProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Distancias (km) y tiempos de manejo (min) entre todos los puntos; fila/columna 0 = origen. */
class Matriz(val km: Array<DoubleArray>, val minutos: Array<DoubleArray>)

/**
 * Matriz de distancias por calles con OSRM (enrutador libre sobre OpenStreetMap), `GET /table`.
 * Devuelve null si está desactivado (`CLEANTOK_OSRM_URL` vacío) o el servicio no responde a tiempo:
 * quien la usa cae a la estimación en línea recta. El servidor público de OSRM es para demos;
 * en producción se levanta uno propio con el extracto de Ecuador.
 */
@Component
class MatrizVial(private val props: CleanTokProperties, private val json: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()

    fun calcular(puntos: List<Punto>): Matriz? {
        val base = props.abastecimiento.osrmUrl.trimEnd('/')
        if (base.isBlank() || puntos.size < 2) return null
        return try {
            val coords = puntos.joinToString(";") { "${it.lng},${it.lat}" }
            val peticion = HttpRequest.newBuilder(URI("$base/table/v1/driving/$coords?annotations=distance,duration"))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "CleanTok/0.1 (hackathon Expo Clean Ecuador)")
                .build()
            val r = json.readTree(http.send(peticion, HttpResponse.BodyHandlers.ofString()).body())
            if (r.path("code").asString() != "Ok") return null
            // Un par sin camino (null) invalida la matriz: mejor la estimación que una ruta incompleta.
            fun tabla(campo: String, factor: Double): Array<DoubleArray>? {
                val celdas = puntos.indices.map { i -> puntos.indices.map { j -> r.path(campo).path(i).path(j) } }
                if (celdas.flatten().any { it.isNull || it.isMissingNode }) return null
                return Array(puntos.size) { i -> DoubleArray(puntos.size) { j -> celdas[i][j].asDouble() / factor } }
            }
            Matriz(tabla("distances", 1000.0) ?: return null, tabla("durations", 60.0) ?: return null)
        } catch (e: Exception) {
            log.warn("OSRM no disponible ({}): se usa la distancia en línea recta", e.javaClass.simpleName)
            null
        }
    }
}
