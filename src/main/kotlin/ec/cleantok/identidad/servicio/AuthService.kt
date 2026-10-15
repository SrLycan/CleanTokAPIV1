package ec.cleantok.identidad.servicio

import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.comun.error.NoAutorizadoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.TokenService
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.identidad.api.EmpresaResumenDto
import ec.cleantok.identidad.api.EstablecimientoAsignadoDto
import ec.cleantok.identidad.api.MeResponse
import ec.cleantok.identidad.api.TokenResponse
import ec.cleantok.identidad.api.UsuarioResumenDto
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.identidad.dominio.RefreshToken
import ec.cleantok.identidad.dominio.RefreshTokenRepository
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Lo implementa el módulo de establecimientos; así identidad no depende de él. */
fun interface EstablecimientosDeUsuario {
    fun de(usuario: UsuarioAutenticado): List<EstablecimientoAsignadoDto>
}

@Service
class AuthService(
    private val usuarios: UsuarioRepository,
    private val empresas: EmpresaRepository,
    private val proveedores: ProveedorRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val establecimientos: ObjectProvider<EstablecimientosDeUsuario>,
    private val props: CleanTokProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    // Hash ficticio para que un email inexistente tarde lo mismo que una contraseña errónea.
    private val hashFicticio by lazy { passwordEncoder.encode("contraseña-ficticia-para-tiempo-constante") }

    @Transactional
    fun login(email: String, password: String): TokenResponse {
        val usuario = usuarios.buscarPorEmail(email.trim())
        val valido = passwordEncoder.matches(password, usuario?.passwordHash ?: hashFicticio)
        if (usuario == null || !valido || !usuario.activo) throw NoAutorizadoException("Email o contraseña incorrectos")
        if (!organizacionActiva(usuario)) throw NoAutorizadoException("Tu organización está desactivada en CleanTok")
        return emitir(usuario, familiaId = UUID.randomUUID())
    }

    /**
     * Rota el refresh token. Si llega uno ya usado o revocado se revoca toda su familia;
     * por eso la revocación no se deshace al lanzar la excepción.
     */
    @Transactional(noRollbackFor = [NoAutorizadoException::class])
    fun refrescar(refreshToken: String): TokenResponse {
        val ahora = clock.instant()
        val actual = refreshTokens.findByTokenHash(hash(refreshToken)) ?: throw NoAutorizadoException()
        if (actual.revocadoEn != null || actual.reemplazadoPor != null) {
            refreshTokens.revocarFamilia(actual.familiaId, ahora)
            throw NoAutorizadoException()
        }
        if (!actual.expiraEn.isAfter(ahora)) throw NoAutorizadoException()
        val usuario = usuarios.findById(actual.usuarioId).orElse(null)
        if (usuario == null || !usuario.activo || !organizacionActiva(usuario)) {
            refreshTokens.revocarFamilia(actual.familiaId, ahora)
            throw NoAutorizadoException()
        }
        val respuesta = emitir(usuario, actual.familiaId)
        actual.revocadoEn = ahora
        actual.reemplazadoPor = refreshTokens.findByTokenHash(hash(respuesta.refreshToken))!!.id
        return respuesta
    }

    @Transactional
    fun logout(refreshToken: String) {
        refreshTokens.findByTokenHash(hash(refreshToken))?.let { refreshTokens.revocarFamilia(it.familiaId, clock.instant()) }
    }

    @Transactional(readOnly = true)
    fun me(autenticado: UsuarioAutenticado): MeResponse {
        val usuario = usuarios.findById(autenticado.id).orElseThrow { NoAutorizadoException() }
        val empresa = usuario.empresaId?.let { empresas.findById(it).orElse(null) }
        val proveedor = usuario.proveedorId?.let { proveedores.findById(it).orElse(null) }
        return MeResponse(
            usuario = resumen(usuario),
            empresa = empresa?.let { EmpresaResumenDto(it.id, it.nombre) },
            proveedor = proveedor?.let { EmpresaResumenDto(it.id, it.nombre) },
            establecimientos = establecimientos.getIfAvailable()?.de(autenticado) ?: emptyList(),
        )
    }

    private fun emitir(usuario: Usuario, familiaId: UUID): TokenResponse {
        val autenticado = UsuarioAutenticado(usuario.id, usuario.empresaId, usuario.rol, usuario.proveedorId)
        val opaco = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        refreshTokens.save(
            RefreshToken(
                usuarioId = usuario.id,
                familiaId = familiaId,
                tokenHash = hash(opaco),
                expiraEn = clock.instant().plus(Duration.ofDays(props.jwt.refreshDias)),
            ),
        )
        return TokenResponse(
            accessToken = tokenService.emitirAcceso(autenticado),
            refreshToken = opaco,
            expiraEn = tokenService.duracionAcceso.seconds,
            usuario = resumen(usuario),
        )
    }

    /** La empresa o el proveedor del usuario siguen activos (el SUPER_ADMIN no tiene organización). */
    private fun organizacionActiva(u: Usuario): Boolean {
        u.empresaId?.let { id -> return empresas.findById(id).map { it.activo }.orElse(false) }
        u.proveedorId?.let { id -> return proveedores.findById(id).map { it.activo }.orElse(false) }
        return true
    }

    private fun resumen(u: Usuario) = UsuarioResumenDto(u.id, u.nombre, u.email, u.rol, u.debeCambiarClave)

    /** Cambio de contraseña del propio usuario (obligatorio tras una clave temporal). La sesión actual sigue abierta. */
    @Transactional
    fun cambiarClave(autenticado: UsuarioAutenticado, actual: String, nueva: String) {
        val usuario = usuarios.findById(autenticado.id).orElseThrow { NoAutorizadoException() }
        if (!passwordEncoder.matches(actual, usuario.passwordHash)) throw ValidacionException("La contraseña actual no es correcta")
        if (actual == nueva) throw ValidacionException("La contraseña nueva debe ser distinta de la actual")
        usuario.passwordHash = passwordEncoder.encode(nueva)!!
        usuario.debeCambiarClave = false
    }

    private fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
}
