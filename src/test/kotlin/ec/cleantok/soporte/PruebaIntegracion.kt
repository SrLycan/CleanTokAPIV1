package ec.cleantok.soporte

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.reflect.KClass

@TestConfiguration(proxyBeanMethods = false)
class ContenedoresPrueba {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
}

/**
 * Base de las pruebas de integración: API completa contra un Postgres 16 real (Testcontainers).
 * Cada prueba crea sus propios datos con [Fabrica], con emails y RUC únicos, así no hace falta limpiar.
 */
// Sin OSRM en las pruebas: las rutas usan la distancia en línea recta y no dependen de internet.
@SpringBootTest(
    properties = [
        "cleantok.alertas.job-habilitado=false", "cleantok.almacenamiento.ruta=build/almacenamiento-pruebas",
        "cleantok.abastecimiento.osrm-url=",
    ],
)
@AutoConfigureMockMvc
@Import(ContenedoresPrueba::class, Fabrica::class)
abstract class PruebaIntegracion {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var fabrica: Fabrica
}

/**
 * Marca la clase de prueba que verifica el aislamiento entre empresas de esos controladores.
 * `CoberturaAislamientoTest` falla si algún controlador de dominio no tiene una.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class VerificaAislamiento(vararg val controladores: KClass<*>)
