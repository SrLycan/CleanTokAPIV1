package ec.cleantok.comun.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cleantok")
data class CleanTokProperties(
    val urlPublica: String,
    val jwt: Jwt,
    val cors: Cors = Cors(),
    val ia: Ia = Ia(),
    val diasCoberturaMin: Int = 7,
    val almacenamiento: Almacenamiento = Almacenamiento(),
    val superAdmin: SuperAdmin = SuperAdmin(),
    val android: Android = Android(),
    val nivel: Nivel = Nivel(),
    val alertas: Alertas = Alertas(),
    val abastecimiento: Abastecimiento = Abastecimiento(),
) {
    data class Jwt(val secret: String, val accesoMinutos: Long = 15, val refreshDias: Long = 7)
    data class Cors(val origenes: List<String> = emptyList())
    data class Ia(
        val habilitada: Boolean = false,
        val modelo: String = "claude-opus-5-5",
        val apiKey: String = "",
        val timeoutSegundos: Long = 60,
    )
    data class Almacenamiento(val tipo: String = "local", val ruta: String = "./almacenamiento", val supabase: Supabase = Supabase())
    data class Supabase(val url: String = "", val serviceKey: String = "", val bucket: String = "")

    /** Job horario que reevalúa las alertas (cobertura, etc.). */
    data class Alertas(val jobHabilitado: Boolean = true, val cron: String = "0 0 * * * *")

    /** Tras un CONSUMO, se sugiere reportar nivel si el piso queda en esta fracción del último traslado o menos. */
    data class Nivel(val umbralSugerencia: java.math.BigDecimal = java.math.BigDecimal("0.25"))

    /** Si se definen, se crea el SUPER_ADMIN al arrancar (solo si no existe). */
    data class SuperAdmin(val email: String = "", val password: String = "")

    /**
     * Cantidad recomendada = consumo diario × (días de entrega + días de ciclo + días de seguridad) − stock actual.
     * Rutas: distancias y tiempos por calles con OSRM en [osrmUrl] (vacío lo desactiva). Si no responde, distancia en
     * línea recta × [factorRuta] a [velocidadKmH] promedio. Más [minutosPorParada] en cada destino.
     * CO₂e = km × [co2KgPorKm] (furgoneta diésel; ajustar según el vehículo).
     */
    data class Abastecimiento(
        val diasEntrega: Int = 3,
        val diasCiclo: Int = 14,
        val diasSeguridad: Int = 3,
        val factorRuta: Double = 1.3,
        val velocidadKmH: Double = 25.0,
        val minutosPorParada: Int = 10,
        val co2KgPorKm: Double = 0.25,
        val osrmUrl: String = "",
    )

    /** App Links de Android: paquete y huellas SHA-256 del certificado de firma. */
    data class Android(val paquete: String = "", val sha256: List<String> = emptyList())
}
