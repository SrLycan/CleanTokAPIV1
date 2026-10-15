package ec.cleantok.soporte

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** JSON de un movimiento para las pruebas. */
fun jsonMovimiento(
    tipo: String,
    productoId: UUID,
    ubicacionId: UUID,
    cantidad: String,
    unidad: String? = null,
    ocurridoEn: OffsetDateTime = OffsetDateTime.now(ZoneOffset.ofHours(-5)),
    clientUuid: UUID = UUID.randomUUID(),
    motivo: String? = null,
    sentido: String? = null,
): String = buildString {
    append("""{"clientUuid":"$clientUuid","tipo":"$tipo","productoId":"$productoId","ubicacionId":"$ubicacionId",""")
    append(""""cantidad":$cantidad,"ocurridoEn":"$ocurridoEn"""")
    unidad?.let { append(""","unidad":"$it"""") }
    motivo?.let { append(""","motivo":"$it"""") }
    sentido?.let { append(""","sentido":"$it"""") }
    append("}")
}

fun MockMvc.mover(
    token: String,
    tipo: String,
    productoId: UUID,
    ubicacionId: UUID,
    cantidad: String,
    unidad: String? = null,
    haceDias: Long = 0,
    clientUuid: UUID = UUID.randomUUID(),
    motivo: String? = null,
    sentido: String? = null,
): ResultActionsDsl = post("/api/v1/movimientos") {
    header("Authorization", token)
    contentType = MediaType.APPLICATION_JSON
    content = jsonMovimiento(
        tipo, productoId, ubicacionId, cantidad, unidad,
        OffsetDateTime.now(ZoneOffset.ofHours(-5)).minus(Duration.ofDays(haceDias)),
        clientUuid, motivo, sentido,
    )
}

/** Igual que [mover] pero exige que se aplique (201). */
fun MockMvc.moverOk(token: String, tipo: String, productoId: UUID, ubicacionId: UUID, cantidad: String, haceDias: Long = 0): String {
    val r = mover(token, tipo, productoId, ubicacionId, cantidad, haceDias = haceDias).andReturn().response
    check(r.status == 201) { "Movimiento $tipo $cantidad no aplicado (${r.status}): ${r.contentAsString}" }
    return JsonPath.read(r.contentAsString, "$.movimiento.id")
}
