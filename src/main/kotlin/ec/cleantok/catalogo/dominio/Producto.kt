package ec.cleantok.catalogo.dominio

import ec.cleantok.comun.Textos
import ec.cleantok.comun.entidad.EntidadBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

enum class Categoria { QUIMICO, PAPEL_Y_HIGIENE, BOLSAS, UTENSILIO, EPP, MAQUINARIA, OTRO }

/** Quién repone el producto: define a quién se avisa en las alertas y quién compra. */
enum class Abastece { EMPRESA_LIMPIEZA, ENTIDAD_CONTRATANTE }

enum class OrigenProducto { PDF, MANUAL }

@Entity
@Table(name = "producto")
class Producto(
    @Column(name = "empresa_id", nullable = false, updatable = false) val empresaId: UUID,
    @Column(name = "establecimiento_id", nullable = false, updatable = false) val establecimientoId: UUID,
    nombre: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var categoria: Categoria,
    @Column(name = "es_consumible", nullable = false) var esConsumible: Boolean,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var unidad: Unidad,
    var presentacion: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var abastece: Abastece = Abastece.EMPRESA_LIMPIEZA,
    @Column(name = "stock_minimo") var stockMinimo: BigDecimal? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val origen: OrigenProducto = OrigenProducto.MANUAL,
    @Column(name = "material_extraido_id") var materialExtraidoId: UUID? = null,
    @Column(nullable = false) var activo: Boolean = true,
) : EntidadBase() {

    @Column(nullable = false)
    var nombre: String = Textos.limpiar(nombre)
        set(valor) {
            field = Textos.limpiar(valor)
            nombreNormalizado = Textos.normalizar(valor)
        }

    @Column(name = "nombre_normalizado", nullable = false)
    var nombreNormalizado: String = Textos.normalizar(nombre)
        protected set
}
