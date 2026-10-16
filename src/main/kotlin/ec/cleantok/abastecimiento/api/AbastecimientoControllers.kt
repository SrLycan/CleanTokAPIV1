package ec.cleantok.abastecimiento.api

import ec.cleantok.abastecimiento.servicio.OrdenCompraService
import ec.cleantok.abastecimiento.servicio.ProveedorService
import ec.cleantok.abastecimiento.servicio.RutaService
import ec.cleantok.abastecimiento.servicio.SolicitudService
import ec.cleantok.abastecimiento.servicio.SugerenciasService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import ec.cleantok.identidad.api.UsuarioDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Compras de la empresa de limpieza: qué pedir, solicitud consolidada, comparación de ofertas y adjudicación. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN_EMPRESA')")
@Tag(name = "Compras (empresa)")
class SolicitudController(
    private val sugerencias: SugerenciasService,
    private val solicitudes: SolicitudService,
    private val proveedores: ProveedorService,
) {
    @GetMapping("/abastecimiento/sugerencias")
    @Operation(
        summary = "Qué reponer y cuánto, en todos los establecimientos",
        description = "Productos que compra la empresa en estado CRITICO o BAJO (o todos con `todos=true`). " +
            "Cantidad = consumo diario × (entrega + ciclo + seguridad) − stock actual. `yaPedido` indica si ya está en una solicitud u orden abierta.",
    )
    fun sugerencias(@RequestParam(defaultValue = "false") todos: Boolean): List<SugerenciaDto> = sugerencias.sugerencias(todos)

    @GetMapping("/proveedores")
    @Operation(summary = "Proveedores con los que trabaja la empresa")
    fun proveedores(): List<ProveedorDto> = proveedores.deMiEmpresa()

    @GetMapping("/proveedores/directorio")
    @Operation(summary = "Proveedores activos de la plataforma, indicando con cuáles ya trabajo")
    fun directorio(): List<DirectorioProveedorDto> = proveedores.directorio()

    @PutMapping("/proveedores/{id}/vinculo")
    @Operation(summary = "Trabajar con este proveedor (o cambiar si es preferido): se le invitará a cotizar")
    fun vincular(@PathVariable id: UUID, @RequestBody(required = false) req: VinculoRequest?): DirectorioProveedorDto =
        proveedores.vincular(id, req?.preferido ?: false)

    @DeleteMapping("/proveedores/{id}/vinculo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Dejar de trabajar con este proveedor (las órdenes ya emitidas siguen su curso)")
    fun desvincular(@PathVariable id: UUID) = proveedores.desvincular(id)

    @PostMapping("/solicitudes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Crea una solicitud consolidada",
        description = "Las líneas son productos de cada establecimiento; se agrupan por nombre y unidad. Con `enviar=true` se invita a cotizar a los proveedores de la empresa.",
    )
    fun crear(@Valid @RequestBody req: CrearSolicitudRequest): SolicitudDto = solicitudes.crear(req)

    @GetMapping("/solicitudes")
    @Operation(summary = "Solicitudes de la empresa (más recientes primero)")
    fun listar(): List<SolicitudResumenDto> = solicitudes.listar()

    @GetMapping("/solicitudes/{id}")
    @Operation(summary = "Solicitud con destinos, ofertas y comparación por producto (puntaje y recomendada)")
    fun obtener(@PathVariable id: UUID): SolicitudDto = solicitudes.obtener(id)

    @PostMapping("/solicitudes/{id}/enviar")
    @Operation(summary = "Envía la solicitud a cotizar a los proveedores vinculados")
    fun enviar(@PathVariable id: UUID): SolicitudDto = solicitudes.enviar(id)

    @PostMapping("/solicitudes/{id}/cancelar")
    @Operation(summary = "Cancela una solicitud en borrador o en cotización")
    fun cancelar(@PathVariable id: UUID): SolicitudDto = solicitudes.cancelar(id)

    @PostMapping("/solicitudes/{id}/adjudicar")
    @Operation(
        summary = "Aprueba la compra: elige proveedor por producto y emite una orden por proveedor",
        description = "Sin `asignaciones` se usa la oferta recomendada de cada producto.",
    )
    fun adjudicar(@PathVariable id: UUID, @Valid @RequestBody(required = false) req: AdjudicarRequest?): SolicitudDto =
        solicitudes.adjudicar(id, req ?: AdjudicarRequest())
}

/** Portal del proveedor: solicitudes para cotizar, mis ofertas y mis repartidores. */
@RestController
@RequestMapping("/api/v1/proveedor")
@PreAuthorize("hasRole('PROVEEDOR')")
@Tag(name = "Proveedor")
class ProveedorController(
    private val solicitudes: SolicitudService,
    private val proveedores: ProveedorService,
) {
    @GetMapping("/perfil")
    @Operation(summary = "Mi empresa proveedora y cómo entrego")
    fun perfil(): ProveedorDto = proveedores.miProveedor()

    @PutMapping("/perfil")
    @Operation(summary = "Actualiza teléfono, local, bodega y formas de entrega")
    fun actualizarPerfil(@Valid @RequestBody req: ActualizarPerfilRequest): ProveedorDto = proveedores.actualizarPerfil(req)

    @GetMapping("/equipo")
    @Operation(summary = "Usuarios de mi proveedor: ventas y repartidores")
    fun equipo(): List<UsuarioDto> = proveedores.miEquipo()

    @PostMapping("/equipo")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Agrega un repartidor (o un usuario de ventas) con clave temporal")
    fun agregar(@Valid @RequestBody req: MiembroRequest): UsuarioDto = proveedores.agregarMiembro(req)

    @PatchMapping("/equipo/{id}")
    @Operation(summary = "Activa o desactiva a alguien de mi equipo (desactivar cierra sus sesiones)")
    fun activar(@PathVariable id: UUID, @Valid @RequestBody req: ActivoRequest): UsuarioDto = proveedores.cambiarActivoMiembro(id, req.activo!!)

    @PostMapping("/equipo/{id}/restablecer-clave")
    @Operation(summary = "Clave temporal nueva para alguien de mi equipo")
    fun restablecer(@PathVariable id: UUID): UsuarioDto = proveedores.restablecerMiembro(id)

    @GetMapping("/solicitudes")
    @Operation(summary = "Solicitudes a las que me invitaron a cotizar")
    fun solicitudes(): List<SolicitudProveedorDto> = solicitudes.invitadas()

    @GetMapping("/solicitudes/{id}")
    @Operation(summary = "Detalle para cotizar. Nunca muestra ofertas de otros proveedores.")
    fun solicitud(@PathVariable id: UUID): SolicitudProveedorDto = solicitudes.obtenerComoProveedor(id)

    @PutMapping("/solicitudes/{id}/oferta")
    @Operation(summary = "Envía o reemplaza mi oferta: precio y disponibilidad por producto, plazo y modalidad de entrega")
    fun ofertar(@PathVariable id: UUID, @Valid @RequestBody req: OfertaRequest): SolicitudProveedorDto = solicitudes.ofertar(id, req)

    @GetMapping("/repartidores")
    @Operation(summary = "Mis repartidores activos")
    fun repartidores(): List<UsuarioRefDto> = proveedores.misRepartidores()
}

/** Órdenes de compra y su rastreo. Cada rol ve las suyas: empresa, encargado (sus destinos), proveedor y repartidor. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO', 'PROVEEDOR', 'REPARTIDOR')")
@Tag(name = "Órdenes de compra y entregas")
class OrdenCompraController(
    private val ordenes: OrdenCompraService,
    private val rutas: RutaService,
) {
    @GetMapping("/ordenes-compra")
    @Operation(summary = "Mis órdenes de compra, según mi rol")
    fun listar(): List<OrdenResumenDto> = ordenes.listar()

    @GetMapping("/ordenes-compra/{id}")
    @Operation(summary = "Orden con línea de tiempo, eventos, entregas por destino y acciones disponibles para mí")
    fun obtener(@PathVariable id: UUID): OrdenDto = ordenes.obtener(id)

    @PostMapping("/ordenes-compra/{id}/estado")
    @Operation(
        summary = "Avanza el estado del pedido",
        description = "Proveedor (la orden llega aprobada y pagada, no se acepta): EN_PREPARACION, EMPACADA, DESPACHADA o LISTA_PARA_RETIRO, EN_TRANSITO, RETIRADA. " +
            "Repartidor: DESPACHADA y EN_TRANSITO. Admin: RETIRADA. `acciones.siguientes` dice cuáles aplican.",
    )
    fun estado(@PathVariable id: UUID, @Valid @RequestBody req: CambiarEstadoRequest): OrdenDto = ordenes.cambiarEstado(id, req.estado!!, req.nota)

    @PostMapping("/ordenes-compra/{id}/repartidor")
    @PreAuthorize("hasRole('PROVEEDOR')")
    @Operation(summary = "Asigna el repartidor (solo envío directo)")
    fun repartidor(@PathVariable id: UUID, @Valid @RequestBody req: AsignarRepartidorRequest): OrdenDto =
        ordenes.asignarRepartidor(id, req.repartidorId!!)

    @PostMapping("/ordenes-compra/{id}/entregas/{entregaId}/entregar")
    @PreAuthorize("hasAnyRole('PROVEEDOR', 'REPARTIDOR')")
    @Operation(summary = "El repartidor dejó el pedido en este destino (queda pendiente de confirmación)")
    fun entregar(@PathVariable id: UUID, @PathVariable entregaId: UUID, @Valid @RequestBody(required = false) req: MarcarEntregadaRequest?): OrdenDto =
        ordenes.marcarEntregada(id, entregaId, req?.nota)

    @PostMapping("/ordenes-compra/{id}/entregas/{entregaId}/recibir")
    @PreAuthorize("hasAnyRole('ADMIN_EMPRESA', 'SUPERVISOR', 'ENCARGADO')")
    @Operation(
        summary = "Confirma la recepción en el destino",
        description = "Registra un INGRESO en la bodega por cada producto recibido (enlazado al kardex). Cuando todos los destinos confirman, la orden queda ENTREGADA.",
    )
    fun recibir(@PathVariable id: UUID, @PathVariable entregaId: UUID, @Valid @RequestBody(required = false) req: RecibirRequest?): OrdenDto =
        ordenes.recibir(id, entregaId, req ?: RecibirRequest())

    @GetMapping("/ordenes-compra/{id}/ruta")
    @Operation(summary = "Ruta de reparto de la orden: base vs. optimizada (km, minutos, CO₂e)")
    fun ruta(@PathVariable id: UUID): RutaDto = rutas.deOrden(ordenes.visible(id))

    @GetMapping("/rutas/mia")
    @PreAuthorize("hasRole('REPARTIDOR')")
    @Operation(summary = "Mi ruta: todas mis órdenes empacadas o en camino, optimizada")
    fun miRuta(): RutaDto = rutas.delRepartidor()
}
