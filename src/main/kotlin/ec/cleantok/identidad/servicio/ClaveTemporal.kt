package ec.cleantok.identidad.servicio

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Claves temporales para cuentas nuevas o restablecidas: 10 caracteres sin ambiguos (sin 0/O, 1/I/L),
 * en dos bloques fáciles de dictar ("KX4TQ-9MHPA"). El usuario debe cambiarla al entrar.
 */
@Component
class ClaveTemporal {
    private val random = SecureRandom()

    fun nueva(): String = (1..10).map { ALFABETO[random.nextInt(ALFABETO.length)] }.joinToString("").let { "${it.take(5)}-${it.drop(5)}" }

    companion object {
        private const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}
