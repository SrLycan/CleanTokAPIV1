package ec.cleantok.soporte

import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.Producto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.catalogo.dominio.Unidad
import ec.cleantok.comun.seguridad.TokenService
import java.math.BigDecimal
import ec.cleantok.comun.seguridad.UsuarioAutenticado
import ec.cleantok.establecimiento.dominio.Establecimiento
import ec.cleantok.establecimiento.dominio.EstablecimientoRepository
import ec.cleantok.establecimiento.dominio.TipoEstablecimiento
import ec.cleantok.establecimiento.dominio.TipoUbicacion
import ec.cleantok.establecimiento.dominio.Ubicacion
import ec.cleantok.establecimiento.dominio.UbicacionRepository
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimiento
import ec.cleantok.establecimiento.dominio.UsuarioEstablecimientoRepository
import ec.cleantok.establecimiento.servicio.GeneradorTokenQr
import ec.cleantok.abastecimiento.dominio.EmpresaProveedor
import ec.cleantok.abastecimiento.dominio.EmpresaProveedorRepository
import ec.cleantok.identidad.dominio.Empresa
import ec.cleantok.identidad.dominio.Proveedor
import ec.cleantok.identidad.dominio.ProveedorRepository
import ec.cleantok.identidad.dominio.EmpresaRepository
import ec.cleantok.identidad.dominio.Rol
import ec.cleantok.identidad.dominio.Usuario
import ec.cleantok.identidad.dominio.UsuarioRepository
import org.springframework.boot.test.context.TestComponent
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Crea datos de prueba únicos y emite tokens sin pasar por el login. */
@TestComponent
class Fabrica(
    private val empresas: EmpresaRepository,
    private val usuarios: UsuarioRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokens: TokenService,
    private val establecimientos: EstablecimientoRepository,
    private val ubicaciones: UbicacionRepository,
    private val asignaciones: UsuarioEstablecimientoRepository,
    private val tokensQr: GeneradorTokenQr,
    private val productos: ProductoRepository,
    private val proveedores: ProveedorRepository,
    private val vinculos: EmpresaProveedorRepository,
) {
    // El hash se calcula una vez: BCrypt es lento a propósito.
    private val hashClave by lazy { passwordEncoder.encode(CLAVE)!! }

    fun empresa(nombre: String = "Limpieza ${sufijo()}"): Empresa =
        empresas.save(Empresa(nombre, ruc = "17" + rucs.incrementAndGet().toString().padStart(9, '0') + "01"))

    fun usuario(empresa: Empresa?, rol: Rol, activo: Boolean = true): Usuario =
        usuarios.save(
            Usuario(
                empresaId = empresa?.id,
                nombre = "${rol.name.lowercase()} ${sufijo()}",
                email = "${rol.name.lowercase()}-${sufijo()}@prueba.ec",
                passwordHash = hashClave,
                rol = rol,
                activo = activo,
            ),
        )

    fun admin(empresa: Empresa) = usuario(empresa, Rol.ADMIN_EMPRESA)

    fun encargado(empresa: Empresa) = usuario(empresa, Rol.ENCARGADO)

    fun superAdmin() = usuario(null, Rol.SUPER_ADMIN)

    /** Proveedor en Quito; por defecto solo hace envío directo. */
    fun proveedor(
        nombre: String = "Proveedor ${sufijo()}",
        envioDirecto: Boolean = true,
        retiroLocal: Boolean = false,
        lat: String = "-0.2000",
        lng: String = "-78.5000",
    ): Proveedor = proveedores.save(
        Proveedor(
            nombre, "09" + rucs.incrementAndGet().toString().padStart(9, '0') + "01", null, "Av. Prueba 123",
            BigDecimal(lat), BigDecimal(lng), envioDirecto = envioDirecto, retiroLocal = retiroLocal,
        ),
    )

    fun usuarioDe(p: Proveedor, rol: Rol = Rol.PROVEEDOR): Usuario =
        usuarios.save(
            Usuario(
                empresaId = null, nombre = "${rol.name.lowercase()} ${sufijo()}", email = "${rol.name.lowercase()}-${sufijo()}@prov.ec",
                passwordHash = hashClave, rol = rol, proveedorId = p.id,
            ),
        )

    fun vincular(e: Empresa, p: Proveedor, preferido: Boolean = false) {
        vinculos.save(EmpresaProveedor(e.id, p.id, preferido))
    }

    fun establecimiento(
        empresa: Empresa,
        nombre: String = "Edificio ${sufijo()}",
        activo: Boolean = true,
        lat: String? = null,
        lng: String? = null,
        creadoPor: Usuario? = null,
    ): Establecimiento {
        val e = establecimientos.save(
            Establecimiento(
                empresa.id, nombre, TipoEstablecimiento.OFICINA, activo = activo, lat = lat?.let(::BigDecimal), lng = lng?.let(::BigDecimal),
                creadoPor = creadoPor?.id,
            ),
        )
        ubicaciones.save(Ubicacion(empresa.id, e.id, "Bodega", TipoUbicacion.BODEGA, 0, tokensQr.nuevo()))
        return e
    }

    fun bodega(e: Establecimiento): Ubicacion = ubicaciones.findByEstablecimientoIdAndTipo(e.id, TipoUbicacion.BODEGA)!!

    fun piso(e: Establecimiento, nombre: String = "Piso ${sufijo()}", activo: Boolean = true): Ubicacion =
        ubicaciones.save(Ubicacion(e.empresaId, e.id, nombre, TipoUbicacion.PISO, ubicaciones.maxOrden(e.id) + 1, tokensQr.nuevo(), activo))

    fun asignar(u: Usuario, e: Establecimiento) {
        asignaciones.save(UsuarioEstablecimiento(e.empresaId, u.id, e.id))
    }

    fun producto(
        e: Establecimiento,
        nombre: String = "Producto ${sufijo()}",
        unidad: Unidad = Unidad.L,
        categoria: Categoria = Categoria.QUIMICO,
        stockMinimo: BigDecimal? = null,
        activo: Boolean = true,
        abastece: Abastece = Abastece.EMPRESA_LIMPIEZA,
    ): Producto = productos.save(
        Producto(
            empresaId = e.empresaId, establecimientoId = e.id, nombre = nombre, categoria = categoria,
            esConsumible = categoria != Categoria.MAQUINARIA, unidad = unidad, stockMinimo = stockMinimo,
            activo = activo, abastece = abastece,
        ),
    )

    fun token(u: Usuario): String = "Bearer " + tokens.emitirAcceso(UsuarioAutenticado(u.id, u.empresaId, u.rol, u.proveedorId))

    private fun sufijo() = UUID.randomUUID().toString().take(8)

    companion object {
        const val CLAVE = "Clave2026!"
        private val rucs = AtomicLong(System.nanoTime() % 1_000_000_000L / 10)
    }
}
