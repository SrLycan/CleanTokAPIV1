package ec.cleantok.ordenes.servicio

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.ordenes.extraccion.ClienteIaMateriales
import ec.cleantok.ordenes.extraccion.EntradaIa
import ec.cleantok.ordenes.extraccion.ExtraccionIaException
import ec.cleantok.ordenes.extraccion.ExtractorIa
import ec.cleantok.ordenes.extraccion.ItemIa
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Base64

/**
 * Extracción con la API de Claude (SDK oficial `anthropic-java`).
 *
 * Salida estructurada con esquema JSON (`output_config.format`): en los modelos actuales el `tool_choice`
 * forzado devuelve 400, y la salida estructurada garantiza igualmente un JSON válido contra el esquema.
 * Timeout de 60 s y 1 reintento. Nunca se escribe en los logs el contenido enviado ni la respuesta.
 */
@Component
class ClienteClaude(
    private val props: CleanTokProperties,
    private val json: JsonMapper,
) : ClienteIaMateriales {

    private val log = LoggerFactory.getLogger(javaClass)

    override val disponible: Boolean get() = props.ia.habilitada && props.ia.apiKey.isNotBlank()

    private val cliente: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(props.ia.apiKey)
            .timeout(Duration.ofSeconds(props.ia.timeoutSegundos))
            .maxRetries(1)
            .build()
    }

    override fun extraer(entrada: EntradaIa): List<ItemIa> {
        if (!disponible) throw ExtraccionIaException("La extracción con IA no está habilitada")
        val bloques = mutableListOf<ContentBlockParam>()
        entrada.pdf?.let { pdf ->
            bloques += ContentBlockParam.ofDocument(
                DocumentBlockParam.builder().source(Base64PdfSource.builder().data(Base64.getEncoder().encodeToString(pdf)).build()).build(),
            )
        }
        val pedido = if (entrada.texto != null) {
            "Extrae los materiales de este texto de una orden de compra:\n\n<documento>\n${entrada.texto}\n</documento>"
        } else {
            "Extrae los materiales del documento PDF adjunto."
        }
        bloques += ContentBlockParam.ofText(TextBlockParam.builder().text(pedido).build())

        val params = MessageCreateParams.builder()
            .model(props.ia.modelo)
            .maxTokens(16000L)
            .system(ExtractorIa.INSTRUCCIONES)
            .addUserMessageOfBlockParams(bloques)
            .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(esquema()).build()).build())
            // Si el modelo rechaza la petición, la API reintenta con otro modelo según la categoría.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()

        val respuesta = try {
            cliente.messages().create(params)
        } catch (e: Exception) {
            log.warn("Fallo la llamada a la IA: {}", e.javaClass.simpleName)
            throw ExtraccionIaException("No se pudo contactar al servicio de IA", e)
        }
        if (respuesta.stopReason().orElse(null) == StopReason.REFUSAL) {
            throw ExtraccionIaException("El servicio de IA rechazó la solicitud")
        }
        if (respuesta.stopReason().orElse(null) == StopReason.MAX_TOKENS) {
            throw ExtraccionIaException("La respuesta de la IA quedó incompleta")
        }
        val texto = respuesta.content().flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }.joinToString("")
        return try {
            json.readTree(texto).path("items").iterator().asSequence().map { n ->
                ItemIa(
                    textoOriginal = n.path("texto_original").asString(""),
                    nombre = n.path("nombre").asString(""),
                    categoria = n.path("categoria").asString("OTRO"),
                    unidadSugerida = n.path("unidad_sugerida").asString("UNIDAD"),
                    presentacion = n.path("presentacion").asString("").ifBlank { null },
                    seccionOrigen = n.path("seccion_origen").asString("MATERIALES"),
                    abastece = n.path("abastece").asString("EMPRESA_LIMPIEZA"),
                    pagina = n.path("pagina").asInt(0),
                )
            }.toList()
        } catch (e: Exception) {
            throw ExtraccionIaException("La respuesta de la IA no tiene el formato esperado", e)
        }
    }

    private fun esquema(): JsonOutputFormat.Schema {
        fun enumDe(valores: List<String>) = mapOf("type" to "string", "enum" to valores)
        val item = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("texto_original", "nombre", "categoria", "unidad_sugerida", "presentacion", "seccion_origen", "abastece", "pagina"),
            "properties" to mapOf(
                "texto_original" to mapOf("type" to "string"),
                "nombre" to mapOf("type" to "string"),
                "categoria" to enumDe(Categoria.entries.map { it.name }),
                "unidad_sugerida" to enumDe(Unidad.entries.map { it.name }),
                "presentacion" to mapOf("type" to "string"),
                "seccion_origen" to enumDe(SeccionOrigen.entries.filter { it != SeccionOrigen.MANUAL }.map { it.name }),
                "abastece" to enumDe(Abastece.entries.map { it.name }),
                "pagina" to mapOf("type" to "integer"),
            ),
        )
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("items")))
            .putAdditionalProperty("properties", JsonValue.from(mapOf("items" to mapOf("type" to "array", "items" to item))))
            .build()
    }
}
