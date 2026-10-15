package ec.cleantok.identidad.servicio

import ec.cleantok.comun.config.CleanTokProperties
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Crea el SUPER_ADMIN definido en CLEANTOK_SUPER_ADMIN_EMAIL / _PASSWORD si aún no existe. */
@Component
class InicializadorSuperAdmin(
    private val props: CleanTokProperties,
    private val usuarios: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val (email, password) = props.superAdmin
        if (email.isBlank() || password.isBlank() || usuarios.existeEmail(email)) return
        usuarios.save(
            Usuario(
                empresaId = null,
                nombre = "Super administrador",
                email = email.trim().lowercase(),
                passwordHash = passwordEncoder.encode(password)!!,
                rol = Rol.SUPER_ADMIN,
            ),
        )
        log.info("SUPER_ADMIN inicial creado")
    }
}
