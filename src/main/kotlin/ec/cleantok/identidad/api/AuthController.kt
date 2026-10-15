package ec.cleantok.identidad.api

import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.identidad.servicio.AuthService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
class AuthController(private val auth: AuthService) {

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Inicia sesión con email y contraseña", description = "Devuelve un access token (15 min) y un refresh token (7 días).")
    fun login(@Valid @RequestBody req: LoginRequest): TokenResponse = auth.login(req.email, req.password)

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
        summary = "Renueva la sesión",
        description = "Rota el refresh token: el anterior queda inválido. Reusar un token ya rotado revoca toda la sesión.",
    )
    fun refresh(@Valid @RequestBody req: RefreshRequest): TokenResponse = auth.refrescar(req.refreshToken)

    @PostMapping("/logout")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cierra la sesión revocando el refresh token")
    fun logout(@Valid @RequestBody req: RefreshRequest) = auth.logout(req.refreshToken)

    @PostMapping("/cambiar-clave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cambia mi contraseña", description = "Obligatorio cuando `debeCambiarClave` es true (cuenta creada o restablecida por un admin).")
    fun cambiarClave(@Valid @RequestBody req: CambiarClaveRequest) = auth.cambiarClave(ContextoSeguridad.usuario(), req.actual, req.nueva)

    @GetMapping("/me")
    @Operation(summary = "Usuario autenticado, su rol, su empresa y los establecimientos donde opera")
    fun me(): MeResponse = auth.me(ContextoSeguridad.usuario())
}
