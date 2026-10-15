package ec.cleantok.ordenes.servicio

import ec.cleantok.archivos.dominio.ArchivoRepository
import ec.cleantok.archivos.dominio.TipoArchivo
import ec.cleantok.archivos.servicio.ArchivoService
import ec.cleantok.catalogo.api.ProductoDto
import ec.cleantok.catalogo.api.ProductoRequest
import ec.cleantok.catalogo.dominio.Abastece
import ec.cleantok.catalogo.dominio.Categoria
import ec.cleantok.catalogo.dominio.OrigenProducto
import ec.cleantok.catalogo.dominio.ProductoRepository
import ec.cleantok.catalogo.servicio.ProductoService
import ec.cleantok.comun.Textos
import ec.cleantok.comun.Zona
import ec.cleantok.comun.error.ApiException
import ec.cleantok.comun.error.CodigoError
import ec.cleantok.comun.error.ConflictoException
import ec.cleantok.comun.error.NoEncontradoException
import ec.cleantok.comun.error.ValidacionException
import ec.cleantok.comun.seguridad.ContextoSeguridad
import ec.cleantok.establecimiento.servicio.AccesoEstablecimiento
import ec.cleantok.ordenes.api.ConfirmacionDto
import ec.cleantok.ordenes.api.EditarMaterialRequest
import ec.cleantok.ordenes.api.FusionarMaterialesRequest
import ec.cleantok.ordenes.api.GrupoMaterialesDto
import ec.cleantok.ordenes.api.MaterialDto
import ec.cleantok.ordenes.api.MaterialManualRequest
import ec.cleantok.ordenes.api.OrdenDto
import ec.cleantok.ordenes.api.ResumenMaterialesDto
import ec.cleantok.ordenes.dominio.EstadoMaterial
import ec.cleantok.ordenes.dominio.EstadoOrden
import ec.cleantok.ordenes.dominio.MaterialExtraido
import ec.cleantok.ordenes.dominio.MaterialExtraidoRepository
import ec.cleantok.ordenes.dominio.MetodoExtraccion
import ec.cleantok.ordenes.dominio.OrdenServicio
import ec.cleantok.ordenes.dominio.OrdenServicioRepository
import ec.cleantok.ordenes.extraccion.ClienteIaMateriales
import ec.cleantok.ordenes.extraccion.Confianza
import ec.cleantok.ordenes.extraccion.OrigenMaterial
import ec.cleantok.ordenes.extraccion.SeccionOrigen
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.Clock
import java.util.UUID

@Service
class OrdenService(
    private val ordenes: OrdenServicioRepository,
    private val materiales: MaterialExtraidoRepository,
    private val archivos: ArchivoService,
    private val archivoRepo: ArchivoRepository,
    private val productos: ProductoRepository,
    private val productoService: ProductoService,
    private val acceso: AccesoEstablecimiento,
    private val clienteIa: ClienteIaMateriales,
    private val eventos: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // ------------------------------------------------------------ carga

    /** Valida el PDF, lo guarda y responde de inmediato; la extracción corre en segundo plano. */
    @Transactional
    fun subir(establecimientoId: UUID, archivo: MultipartFile, permitirEnvioPdfIa: Boolean): OrdenDto {
        val e = acceso.deMiEmpresa(establecimientoId)
        val bytes = archivo.bytes
        validarPdf(bytes, archivo.contentType)
        val sha = ArchivoService.sha256(bytes)
        ordenes.findByEstablecimientoIdAndSha256(e.id, sha)?.let { duplicado(it.id) }
        val a = archivos.guardar(TipoArchivo.PDF_ORDEN, bytes, "application/pdf", "pdf", archivo.originalFilename)
        val o = try {
            ordenes.saveAndFlush(OrdenServicio(e.empresaId, e.id, a.id, sha, ContextoSeguridad.usuario().id, permitirEnvioPdfIa))
        } catch (ex: DataIntegrityViolationException) {
            ordenes.findByEstablecimientoIdAndSha256(e.id, sha)?.let { duplicado(it.id) } ?: throw ex
        }
        eventos.publishEvent(OrdenPorProcesar(o.id))
        return dto(o)
    }

    private fun duplicado(id: UUID): Nothing =
        throw ApiException(HttpStatus.CONFLICT, CodigoError.PDF_DUPLICADO, "Este PDF ya se subió a este establecimiento", mapOf("ordenId" to id))

    private fun validarPdf(bytes: ByteArray, mime: String?) {
        fun invalido(m: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, CodigoError.PDF_INVALIDO, m)
        if (bytes.isEmpty()) invalido("El archivo está vacío")
        if (bytes.size > MAX_BYTES) invalido("El PDF supera el máximo de 15 MB")
        if (mime != null && mime !in MIMES_PDF) invalido("El archivo debe ser un PDF")
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.US_ASCII) != "%PDF-") invalido("El archivo no es un PDF válido")
        try {
            Loader.loadPDF(bytes).use { if (it.isEncrypted) invalido("El PDF está protegido o cifrado; súbelo sin contraseña") }
        } catch (e: InvalidPasswordException) {
            invalido("El PDF está protegido con contraseña; súbelo sin contraseña")
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            invalido("No se pudo leer el PDF; puede estar dañado")
        }
    }

    // ------------------------------------------------------------ consulta

    @Transactional(readOnly = true)
    fun obtener(id: UUID): OrdenDto = dto(deMiEmpresa(id))

    @Transactional(readOnly = true)
    fun listar(establecimientoId: UUID, pageable: Pageable): Page<OrdenDto> {
        val e = acceso.deMiEmpresa(establecimientoId)
        val pagina = ordenes.findAllByEstablecimientoIdOrderByCreatedAtDesc(e.id, pageable)
        return PageImpl(pagina.content.map(::dto), pagina.pageable, pagina.totalElements)
    }

    @Transactional(readOnly = true)
    fun texto(id: UUID): String? = deMiEmpresa(id).textoExtraido

    @Transactional(readOnly = true)
    fun pdf(id: UUID): Pair<String, ByteArray> {
        val o = deMiEmpresa(id)
        val a = archivos.deMiEmpresa(o.archivoId)
        return (a.nombreOriginal ?: "orden.pdf") to archivos.contenido(a)
    }

    @Transactional(readOnly = true)
    fun materiales(ordenId: UUID): List<GrupoMaterialesDto> {
        val o = deMiEmpresa(ordenId)
        return materiales.findAllByOrdenServicioIdOrderByPosicion(o.id)
            .groupBy { it.seccion }
            .toSortedMap(compareBy { it.ordinal })
            .map { (s, l) -> GrupoMaterialesDto(s, l.map(::dto)) }
    }

    // ------------------------------------------------------------ revisión

    @Transactional
    fun editar(materialId: UUID, req: EditarMaterialRequest): MaterialDto {
        val m = materiales.findByIdAndEmpresaId(materialId, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Material")
        exigirEditable(deMiEmpresa(m.ordenServicioId))
        var cambio = false
        req.nombre?.let { m.nombre = Textos.limpiar(it); cambio = true }
        req.categoria?.let { m.categoria = it; cambio = true }
        req.unidad?.let { m.unidad = it; cambio = true }
        req.presentacion?.let { m.presentacion = Textos.limpiar(it).ifBlank { null }; cambio = true }
        req.abastece?.let { m.abastece = it; cambio = true }
        req.esConsumible?.let { m.esConsumible = it; cambio = true }
        m.estado = when {
            req.estado == EstadoMaterial.DESCARTADO -> EstadoMaterial.DESCARTADO
            cambio -> EstadoMaterial.EDITADO
            req.estado != null -> req.estado
            else -> m.estado
        }
        return dto(m)
    }

    /** El primero se conserva (con el nombre indicado); los demás se descartan. */
    @Transactional
    fun fusionar(ordenId: UUID, req: FusionarMaterialesRequest): List<GrupoMaterialesDto> {
        val o = deMiEmpresa(ordenId)
        exigirEditable(o)
        val ids = req.materialIds!!.distinct()
        if (ids.size < 2) throw ValidacionException("Indica al menos dos materiales distintos")
        val lista = ids.map { id ->
            materiales.findByIdAndEmpresaId(id, o.empresaId)?.takeIf { it.ordenServicioId == o.id } ?: throw NoEncontradoException("Material")
        }
        val principal = lista.first()
        req.nombre?.let { principal.nombre = Textos.limpiar(it) }
        principal.estado = EstadoMaterial.EDITADO
        principal.fusionarConId = null
        principal.motivoRevision = "fusión de ${lista.size} ítems"
        lista.drop(1).forEach {
            it.estado = EstadoMaterial.DESCARTADO
            it.fusionarConId = principal.id
            it.motivoRevision = "fusionado en \"${principal.nombre}\""
        }
        return materiales(o.id)
    }

    /** Agrega un producto que no vino en el PDF. También saca de ERROR a una orden sin materiales. */
    @Transactional
    fun agregarManual(ordenId: UUID, req: MaterialManualRequest): MaterialDto {
        val o = deMiEmpresa(ordenId)
        if (o.estado == EstadoOrden.ERROR) {
            o.estado = EstadoOrden.PENDIENTE_REVISION
            o.errorDetalle = null
            if (o.metodoExtraccion == null) o.metodoExtraccion = MetodoExtraccion.MANUAL
        }
        exigirEditable(o)
        val m = materiales.save(
            MaterialExtraido(
                empresaId = o.empresaId,
                ordenServicioId = o.id,
                posicion = materiales.maxPosicion(o.id) + 1,
                textoOriginal = null,
                nombre = Textos.limpiar(req.nombre),
                categoria = req.categoria,
                unidad = req.unidad,
                presentacion = req.presentacion?.let(Textos::limpiar)?.ifBlank { null },
                seccion = SeccionOrigen.MANUAL,
                abastece = req.abastece ?: Abastece.EMPRESA_LIMPIEZA,
                esConsumible = req.esConsumible ?: (req.categoria != Categoria.MAQUINARIA),
                pagina = null,
                origen = OrigenMaterial.MANUAL,
                confianza = Confianza.ALTA,
                motivoRevision = "agregado por el administrador",
                seleccionadoPorDefecto = true,
            ),
        )
        m.estado = EstadoMaterial.ACEPTADO
        return dto(m)
    }

    @Transactional
    fun reintentarIa(ordenId: UUID, permitirEnvioPdfIa: Boolean?): OrdenDto {
        val o = deMiEmpresa(ordenId)
        if (o.estado == EstadoOrden.CONFIRMADA) throw ConflictoException("La orden ya fue confirmada")
        if (o.estado == EstadoOrden.PROCESANDO) throw ConflictoException("La orden se está procesando")
        if (!clienteIa.disponible) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, CodigoError.EXTRACCION_FALLIDA, "La extracción con IA no está habilitada en este servidor")
        }
        permitirEnvioPdfIa?.let { o.permitirEnvioPdfIa = it }
        o.forzarIa = true
        o.estado = EstadoOrden.PROCESANDO
        eventos.publishEvent(OrdenPorProcesar(o.id))
        return dto(o)
    }

    /**
     * Crea los productos (stock 0) a partir de los materiales seleccionados. Idempotente: una segunda
     * llamada devuelve lo ya creado. Si ya existe un producto con el mismo nombre, se enlaza en vez de duplicar.
     */
    @Transactional
    fun confirmar(ordenId: UUID): ConfirmacionDto {
        deMiEmpresa(ordenId)
        val o = ordenes.bloquear(ordenId)!!
        val lista = materiales.findAllByOrdenServicioIdOrderByPosicion(o.id)
        if (o.estado == EstadoOrden.CONFIRMADA) {
            val ids = lista.mapNotNull { it.productoId }.distinct()
            return ConfirmacionDto(o.id, o.estado, true, 0, 0, productos.findAllById(ids).map(ProductoDto::de))
        }
        if (o.estado != EstadoOrden.PENDIENTE_REVISION) throw ConflictoException("La orden debe estar en revisión para confirmarla (estado actual: ${o.estado})")
        val seleccionados = lista.filter { it.seleccionado }
        if (seleccionados.isEmpty()) throw ValidacionException("No hay materiales seleccionados para crear productos")
        val e = acceso.deMiEmpresa(o.establecimientoId)
        var creados = 0
        var vinculados = 0
        val resultado = seleccionados.map { m ->
            val existente = productos.findByEstablecimientoIdAndNombreNormalizado(e.id, Textos.normalizar(m.nombre))
            val p = if (existente != null) {
                vinculados++
                existente
            } else {
                creados++
                productoService.crear(
                    e,
                    ProductoRequest(m.nombre, m.categoria, m.esConsumible, m.unidad, m.presentacion, m.abastece, null),
                    OrigenProducto.PDF,
                    m.id,
                )
            }
            m.productoId = p.id
            if (m.estado == EstadoMaterial.PENDIENTE) m.estado = EstadoMaterial.ACEPTADO
            p
        }
        o.estado = EstadoOrden.CONFIRMADA
        o.confirmadaEn = clock.instant()
        o.confirmadaPor = ContextoSeguridad.usuario().id
        return ConfirmacionDto(o.id, o.estado, false, creados, vinculados, resultado.distinctBy { it.id }.map(ProductoDto::de))
    }

    // ------------------------------------------------------------ utilidades

    private fun exigirEditable(o: OrdenServicio) {
        when (o.estado) {
            EstadoOrden.CONFIRMADA -> throw ConflictoException("La orden ya fue confirmada: edita los productos directamente")
            EstadoOrden.PROCESANDO -> throw ConflictoException("La orden todavía se está procesando")
            else -> Unit
        }
    }

    /** Orden de mi empresa, en un establecimiento que puedo ver; si no, 404. */
    fun deMiEmpresa(id: UUID): OrdenServicio {
        val o = ordenes.findByIdAndEmpresaId(id, ContextoSeguridad.empresaId()) ?: throw NoEncontradoException("Orden")
        acceso.deMiEmpresa(o.establecimientoId)
        return o
    }

    private fun dto(o: OrdenServicio): OrdenDto {
        val lista = materiales.findAllByOrdenServicioIdOrderByPosicion(o.id)
        val resumen = ResumenMaterialesDto(
            total = lista.size,
            seleccionados = lista.count { it.seleccionado },
            confianzaAlta = lista.count { it.confianza == Confianza.ALTA },
            confianzaMedia = lista.count { it.confianza == Confianza.MEDIA },
            confianzaBaja = lista.count { it.confianza == Confianza.BAJA },
            porSeccion = lista.groupingBy { it.seccion }.eachCount(),
        )
        return OrdenDto(
            id = o.id,
            establecimientoId = o.establecimientoId,
            estado = o.estado,
            numeroOrden = o.numeroOrden,
            entidadContratante = o.entidadContratante,
            fechaEmision = o.fechaEmision,
            areaM2 = o.areaM2,
            descripcionServicio = o.descripcionServicio,
            vigenciaMeses = o.vigenciaMeses,
            formatoDetectado = o.formatoDetectado,
            metodoExtraccion = o.metodoExtraccion?.valor,
            tieneCapaTexto = o.tieneCapaTexto,
            permitirEnvioPdfIa = o.permitirEnvioPdfIa,
            avisos = o.avisos,
            errorDetalle = o.errorDetalle,
            nombreArchivo = archivoRepo.findById(o.archivoId).map { it.nombreOriginal }.orElse(null),
            creadaEn = Zona.presentar(o.createdAt),
            confirmadaEn = o.confirmadaEn?.let(Zona::presentar),
            resumen = resumen,
        )
    }

    private fun dto(m: MaterialExtraido) = MaterialDto(
        m.id, m.posicion, m.textoOriginal, m.nombre, m.categoria, m.unidad, m.presentacion, m.seccion, m.abastece,
        m.esConsumible, m.pagina, m.origen, m.confianza, m.motivoRevision, m.seleccionadoPorDefecto, m.seleccionado,
        m.estado, m.fusionarConId, m.productoId,
    )

    companion object {
        const val MAX_BYTES = 15 * 1024 * 1024
        private val MIMES_PDF = setOf("application/pdf", "application/x-pdf", "application/acrobat", "application/octet-stream")
    }
}
