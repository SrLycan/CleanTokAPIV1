package ec.cleantok.comun.seguridad

import ec.cleantok.comun.config.CleanTokProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/** Emite JWT de acceso (HS256). Los refresh tokens son opacos y viven en la base (ver AuthService). */
@Service
class TokenService(
    private val encoder: JwtEncoder,
    private val props: CleanTokProperties,
    private val clock: Clock,
) {
    val duracionAcceso: Duration get() = Duration.ofMinutes(props.jwt.accesoMinutos)

    fun emitirAcceso(usuario: UsuarioAutenticado): String {
        val ahora = clock.instant()
        val claims = JwtClaimsSet.builder()
            .issuer(EMISOR)
            .subject(usuario.id.toString())
            .issuedAt(ahora)
            .expiresAt(ahora.plus(duracionAcceso))
            .claim(CLAIM_ROL, usuario.rol.name)
            .apply { usuario.empresaId?.let { claim(CLAIM_EMPRESA, it.toString()) } }
            .apply { usuario.proveedorId?.let { claim(CLAIM_PROVEEDOR, it.toString()) } }
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    companion object {
        const val EMISOR = "cleantok"
        const val CLAIM_ROL = "rol"
        const val CLAIM_EMPRESA = "emp"
        const val CLAIM_PROVEEDOR = "prov"
    }
}
