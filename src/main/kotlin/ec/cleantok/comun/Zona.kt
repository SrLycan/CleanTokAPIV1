package ec.cleantok.comun

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Se guarda todo en UTC; se presenta en la hora de Ecuador. */
object Zona {
    val ECUADOR: ZoneId = ZoneId.of("America/Guayaquil")

    fun presentar(instante: Instant): OffsetDateTime = instante.atZone(ECUADOR).toOffsetDateTime()
}
