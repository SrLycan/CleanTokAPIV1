package ec.cleantok.catalogo.servicio

import ec.cleantok.catalogo.api.ProductoDto
import ec.cleantok.catalogo.api.ProductoRequest
import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.OrigenProducto
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.comun.Textos
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.dominio.Establecimiento
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProductoService(
    private val productos: ProductoRepository,
    private val acceso: AccesoEstablecimiento,
    private val jdbc: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun listar(
        establecimientoId: UUID,
        activo: Boolean?,
        categoria: Categoria?,
        abastece: Abastece?,
        q: String?,
        pageable: Pageable,
    ): Page<ProductoDto> {
        val e = acceso.visible(establecimientoId)
        // El encargado solo ve productos activos.
        val soloActivos = if (ContextoSeguridad.usuario().esAdmin) activo else true
        val spec = Specification<Producto> { root, _, cb ->
            val filtros = mutableListOf<Predicate>(cb.equal(root.get<UUID>("establecimientoId"), e.id))
            soloActivos?.let { filtros += cb.equal(root.get<Boolean>("activo"), it) }
            categoria?.let { filtros += cb.equal(root.get<Categoria>("categoria"), it) }
            abastece?.let { filtros += cb.equal(root.get<Abastece>("abastece"), it) }
            q?.takeIf { it.isNotBlank() }?.let { filtros += cb.like(root.get("nombreNormalizado"), "%" + Textos.normalizar(it) + "%") }
            cb.and(*filtros.toTypedArray())
        }
        return productos.findAll(spec, pageable).map(ProductoDto::de)
    }

    @Transactional(readOnly = true)
    fun obtener(id: UUID): ProductoDto {
        val p = deMiEmpresa(id)
        acceso.visible(p.establecimientoId)
        if (!p.activo && !ContextoSeguridad.usuario().esAdmin) throw NoEncontradoException("Producto")
        return ProductoDto.de(p)
    }

    @Transactional
    fun crearManual(establecimientoId: UUID, req: ProductoRequest): ProductoDto =
        ProductoDto.de(crear(acceso.deMiEmpresa(establecimientoId), req, OrigenProducto.MANUAL, null))

    /** Punto único de creación (manual o desde la orden PDF). Siempre nace con stock 0. */
    fun crear(e: Establecimiento, req: ProductoRequest, origen: OrigenProducto, materialExtraidoId: UUID?): Producto {
        exigirNombreLibre(e.id, req.nombre, null)
        return productos.save(
            Producto(
                empresaId = e.empresaId,
                establecimientoId = e.id,
                nombre = req.nombre,
                categoria = req.categoria,
                esConsumible = req.esConsumible ?: (req.categoria != Categoria.MAQUINARIA),
                unidad = req.unidad,
                presentacion = req.presentacion?.let(Textos::limpiar)?.ifBlank { null },
                abastece = req.abastece ?: Abastece.EMPRESA_LIMPIEZA,
                stockMinimo = req.stockMinimo,
                origen = origen,
                materialExtraidoId = materialExtraidoId,
            ),
        )
    }

    @Transactional
    fun actualizar(id: UUID, req: ProductoRequest): ProductoDto {
        val p = deMiEmpresa(id)
        exigirNombreLibre(p.establecimientoId, req.nombre, p.id)
        // Los saldos y el kardex están en la unidad del producto: cambiarla los volvería incoherentes.
        if (req.unidad != p.unidad && tieneMovimientos(p.id)) {
            throw ValidacionException("No se puede cambiar la unidad de un producto con movimientos; crea uno nuevo")
        }
        p.nombre = req.nombre
        p.categoria = req.categoria
        req.esConsumible?.let { p.esConsumible = it }
        p.unidad = req.unidad
        p.presentacion = req.presentacion?.let(Textos::limpiar)?.ifBlank { null }
        req.abastece?.let { p.abastece = it }
        p.stockMinimo = req.stockMinimo
        return ProductoDto.de(p)
    }

    @Transactional
    fun cambiarActivo(id: UUID, activo: Boolean): ProductoDto {
        val p = deMiEmpresa(id)
        p.activo = activo
        return ProductoDto.de(p)
    }

    /** Solo se borran productos sin movimientos (por ejemplo, creados por error). Si no, se desactivan. */
    @Transactional
    fun eliminar(id: UUID) {
        val p = deMiEmpresa(id)
        if (tieneMovimientos(p.id)) throw ConflictoException("El producto tiene movimientos: desactívalo en lugar de borrarlo")
        jdbc.update("delete from stock where producto_id = ?", p.id)
        productos.delete(p)
    }

    fun deMiEmpresa(id: UUID): Producto =
        productos.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Producto")

    fun tieneMovimientos(productoId: UUID): Boolean =
        jdbc.queryForObject("select exists(select 1 from movimiento where producto_id = ?)", Boolean::class.java, productoId) == true

    private fun exigirNombreLibre(establecimientoId: UUID, nombre: String, excepto: UUID?) {
        val existente = productos.findByEstablecimientoIdAndNombreNormalizado(establecimientoId, Textos.normalizar(nombre))
        if (existente != null && existente.id != excepto) {
            throw ConflictoException("Ya existe un producto con ese nombre en este establecimiento", mapOf("productoId" to existente.id))
        }
    }
}
