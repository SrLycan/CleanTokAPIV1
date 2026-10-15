package ec.cleantok.catalogo.dominio

import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.math.RoundingMode

enum class Magnitud { VOLUMEN, MASA, ROLLO, UNIDAD, PAQUETE, PAR }

/**
 * Unidades de los productos. Solo se convierten dentro de la misma magnitud
 * (L↔ML, KG↔G, GALON↔L con 1 galón = 3,785 L); las demás no tienen conversión.
 */
enum class Unidad(val magnitud: Magnitud, private val factorBase: BigDecimal) {
    L(Magnitud.VOLUMEN, BigDecimal.ONE),
    ML(Magnitud.VOLUMEN, BigDecimal("0.001")),
    GALON(Magnitud.VOLUMEN, BigDecimal("3.785")),
    KG(Magnitud.MASA, BigDecimal.ONE),
    G(Magnitud.MASA, BigDecimal("0.001")),
    ROLLO(Magnitud.ROLLO, BigDecimal.ONE),
    UNIDAD(Magnitud.UNIDAD, BigDecimal.ONE),
    PAQUETE(Magnitud.PAQUETE, BigDecimal.ONE),
    PAR(Magnitud.PAR, BigDecimal.ONE),
    ;

    fun compatibles(): List<Unidad> = entries.filter { it.magnitud == magnitud }

    /** Convierte [cantidad] expresada en esta unidad a [destino], redondeando a 3 decimales. */
    fun convertir(cantidad: BigDecimal, destino: Unidad): BigDecimal {
        if (destino.magnitud != magnitud) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                CodigoError.UNIDAD_INCOMPATIBLE,
                "No se puede convertir $name a ${destino.name}",
                mapOf("unidadProducto" to destino.name, "unidadesPermitidas" to destino.compatibles().map { it.name }),
            )
        }
        if (destino == this) return cantidad.setScale(3, RoundingMode.HALF_UP)
        return cantidad.multiply(factorBase).divide(destino.factorBase, 3, RoundingMode.HALF_UP)
    }
}
