package ec.cleantok.comun.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("CleanTok API")
                .version("v1")
                .description(
                    "Inventario y trazabilidad de insumos de limpieza. Contrato para la app Android y el panel web.\n\n" +
                        "Los errores siguen RFC 7807 (`application/problem+json`) con un campo `codigo` estable: " +
                        "STOCK_INSUFICIENTE, QR_INVALIDO, QR_NO_ASIGNADO, PRODUCTO_INACTIVO, UNIDAD_INCOMPATIBLE, " +
                        "PDF_INVALIDO, PDF_DUPLICADO, EXTRACCION_FALLIDA, VALIDACION, NO_AUTORIZADO, NO_ENCONTRADO, " +
                        "CONFLICTO, ERROR_INTERNO.",
                ),
        )
        .components(
            Components().addSecuritySchemes(
                "bearer",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList("bearer"))
}
