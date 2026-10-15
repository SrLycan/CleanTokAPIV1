package ec.cleantok.comun.error

import org.springframework.http.HttpStatus

/**
 * Error de negocio que se traduce a ProblemDetail. [extras] se agrega como propiedades
 * del cuerpo (por ejemplo `disponible` en STOCK_INSUFICIENTE).
 */
open class ApiException(
    val status: HttpStatus,
    val codigo: CodigoError,
    override val message: String,
    val extras: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

class NoEncontradoException(recurso: String) :
    ApiException(HttpStatus.NOT_FOUND, CodigoError.NO_ENCONTRADO, "$recurso no encontrado")

class ValidacionException(mensaje: String, extras: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, mensaje, extras)

class ConflictoException(mensaje: String, extras: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.CONFLICT, CodigoError.CONFLICTO, mensaje, extras)

class NoAutorizadoException(mensaje: String = "Credenciales inválidas o sesión expirada") :
    ApiException(HttpStatus.UNAUTHORIZED, CodigoError.NO_AUTORIZADO, mensaje)
