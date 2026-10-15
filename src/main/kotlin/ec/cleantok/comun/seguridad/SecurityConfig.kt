package ec.cleantok.comun.seguridad

import com.nimbusds.jose.jwk.source.ImmutableSecret
import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.identidad.dominio.Rol
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.servlet.HandlerExceptionResolver
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableMethodSecurity
class SecurityConfig(private val props: CleanTokProperties) {

    private val clave: SecretKey by lazy {
        val bytes = props.jwt.secret.toByteArray(Charsets.UTF_8)
        require(bytes.size >= 32) { "JWT_SECRET debe tener al menos 32 bytes" }
        SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
    ): SecurityFilterChain {
        // Los errores de seguridad se delegan al @RestControllerAdvice para responder ProblemDetail.
        http
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                it.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                it.requestMatchers("/actuator/health/**", "/q/**", "/.well-known/**", "/error").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.jwtAuthenticationConverter(::convertir) }
                rs.authenticationEntryPoint { req, res, ex -> resolver.resolveException(req, res, null, ex) }
                rs.accessDeniedHandler { req, res, ex -> resolver.resolveException(req, res, null, ex) }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { req, res, ex -> resolver.resolveException(req, res, null, ex) }
                it.accessDeniedHandler { req, res, ex -> resolver.resolveException(req, res, null, ex) }
            }
        return http.build()
    }

    private fun convertir(jwt: Jwt): AutenticacionJwt {
        val usuario = UsuarioAutenticado(
            id = UUID.fromString(jwt.subject),
            empresaId = jwt.getClaimAsString(TokenService.CLAIM_EMPRESA)?.let(UUID::fromString),
            rol = Rol.valueOf(jwt.getClaimAsString(TokenService.CLAIM_ROL) ?: throw BadJwtException("Token sin rol")),
            proveedorId = jwt.getClaimAsString(TokenService.CLAIM_PROVEEDOR)?.let(UUID::fromString),
        )
        return AutenticacionJwt(usuario, jwt)
    }

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(clave))

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = NimbusJwtDecoder.withSecretKey(clave).macAlgorithm(MacAlgorithm.HS256).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(TokenService.EMISOR)),
        )
        return decoder
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = props.cors.origenes
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("Location", "Content-Disposition")
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }
}
