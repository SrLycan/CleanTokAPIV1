package ec.cleantok.comun.error

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/** Traduce toda excepción a RFC 7807 con un `codigo` estable y mensajes en español. */
@RestControllerAdvice
class ManejadorErrores : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun manejarApi(ex: ApiException): ResponseEntity<ProblemDetail> =
        responder(ex.status, ex.codigo, ex.message, ex.extras)

    @ExceptionHandler(AuthenticationException::class)
    fun manejarAutenticacion(ex: AuthenticationException): ResponseEntity<ProblemDetail> =
        responder(HttpStatus.UNAUTHORIZED, CodigoError.NO_AUTORIZADO, "Debes iniciar sesión")

    @ExceptionHandler(AccessDeniedException::class)
    fun manejarAccesoDenegado(ex: AccessDeniedException): ResponseEntity<ProblemDetail> =
        responder(HttpStatus.FORBIDDEN, CodigoError.NO_AUTORIZADO, "No tienes permiso para esta operación")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun manejarTipo(ex: MethodArgumentTypeMismatchException): ResponseEntity<ProblemDetail> =
        responder(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El parámetro '${ex.name}' tiene un formato inválido")

    @ExceptionHandler(ConstraintViolationException::class)
    fun manejarRestriccion(ex: ConstraintViolationException): ResponseEntity<ProblemDetail> {
        val errores = ex.constraintViolations.map {
            mapOf("campo" to it.propertyPath.toString().substringAfterLast('.'), "mensaje" to it.message)
        }
        return responder(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Datos inválidos", mapOf("errores" to errores))
    }

    @ExceptionHandler(Exception::class)
    fun manejarInesperado(ex: Exception): ResponseEntity<ProblemDetail> {
        // Solo la traza técnica: nunca el cuerpo de la petición.
        log.error("Error no controlado: {}", ex.javaClass.name, ex)
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, CodigoError.ERROR_INTERNO, "Ocurrió un error inesperado")
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errores = ex.bindingResult.fieldErrors.map { mapOf("campo" to it.field, "mensaje" to it.defaultMessage) } +
            ex.bindingResult.globalErrors.map { mapOf("campo" to it.objectName, "mensaje" to it.defaultMessage) }
        return validacion(errores)
    }

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errores = ex.parameterValidationResults.flatMap { r ->
            r.resolvableErrors.map { mapOf("campo" to r.methodParameter.parameterName, "mensaje" to it.defaultMessage) }
        }
        return validacion(errores)
    }

    /** Errores propios de Spring MVC (JSON mal formado, ruta inexistente, 405, 413…): se les agrega `codigo`. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val codigo = when (statusCode.value()) {
            401, 403 -> CodigoError.NO_AUTORIZADO
            404 -> CodigoError.NO_ENCONTRADO
            409 -> CodigoError.CONFLICTO
            else -> if (statusCode.is5xxServerError) CodigoError.ERROR_INTERNO else CodigoError.VALIDACION
        }
        val detalle = when (statusCode.value()) {
            400 -> "La petición es inválida o el JSON está mal formado"
            404 -> "Recurso no encontrado"
            405 -> "Método HTTP no permitido"
            413 -> "El archivo supera el tamaño máximo permitido"
            415 -> "Tipo de contenido no soportado"
            else -> "Error en la petición"
        }
        val problema = ProblemDetail.forStatusAndDetail(statusCode, detalle)
        problema.setProperty("codigo", codigo.name)
        return ResponseEntity(problema, headers, statusCode)
    }

    private fun validacion(errores: List<Map<String, Any?>>): ResponseEntity<Any> {
        val problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Datos inválidos")
        problema.setProperty("codigo", CodigoError.VALIDACION.name)
        problema.setProperty("errores", errores)
        return ResponseEntity.badRequest().body(problema)
    }

    private fun responder(
        status: HttpStatus,
        codigo: CodigoError,
        detalle: String,
        extras: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<ProblemDetail> {
        val problema = ProblemDetail.forStatusAndDetail(status, detalle)
        problema.setProperty("codigo", codigo.name)
        extras.forEach { (k, v) -> problema.setProperty(k, v) }
        return ResponseEntity.status(status).body(problema)
    }
}
