package ec.cleantok.monitoreo.dominio

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReporteNivelRepository : JpaRepository<ReporteNivel, UUID> {
    fun findByClientUuid(clientUuid: UUID): ReporteNivel?

    fun findFirstByProductoIdAndUbicacionIdOrderByOcurridoEnDescRegistradoEnDesc(productoId: UUID, ubicacionId: UUID): ReporteNivel?

    @Query("select distinct r.ubicacionId from ReporteNivel r where r.productoId = :productoId")
    fun ubicacionesConReportes(productoId: UUID): List<UUID>

    fun findAllByEstablecimientoIdOrderByRegistradoEnDesc(establecimientoId: UUID, pageable: Pageable): Page<ReporteNivel>
}

interface AlertaRepository : JpaRepository<Alerta, UUID>, JpaSpecificationExecutor<Alerta> {
    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Alerta?

    fun findByTipoAndProductoIdAndUbicacionIdAndEstado(tipo: TipoAlerta, productoId: UUID, ubicacionId: UUID, estado: EstadoAlerta): Alerta?

    fun findByTipoAndProductoIdAndUbicacionIdIsNullAndEstado(tipo: TipoAlerta, productoId: UUID, estado: EstadoAlerta): Alerta?

    fun findFirstByTipoAndProductoIdAndUbicacionIdOrderByCreadaEnDesc(tipo: TipoAlerta, productoId: UUID, ubicacionId: UUID): Alerta?

    fun findFirstByTipoAndProductoIdAndUbicacionIdIsNullOrderByCreadaEnDesc(tipo: TipoAlerta, productoId: UUID): Alerta?

    @Query("select a.ubicacionId from Alerta a where a.productoId = :productoId and a.tipo = :tipo and a.estado = 'ABIERTA'")
    fun ubicacionesConAbiertas(productoId: UUID, tipo: TipoAlerta): List<UUID>

    fun findAllByUbicacionIdAndEstadoOrderByCreadaEnDesc(ubicacionId: UUID, estado: EstadoAlerta): List<Alerta>

    fun findAllByEstablecimientoIdAndUbicacionIdIsNullAndEstadoOrderByCreadaEnDesc(establecimientoId: UUID, estado: EstadoAlerta): List<Alerta>

    /** Tras desaparecer la condición, las alertas cerradas a mano vuelven a poder abrirse. */
    @Modifying
    @Query(
        """
        update Alerta a set a.rearmada = true
        where a.tipo = :tipo and a.productoId = :productoId and a.ubicacionId = :ubicacionId
          and a.cierre = 'MANUAL' and a.rearmada = false
        """,
    )
    fun rearmar(tipo: TipoAlerta, productoId: UUID, ubicacionId: UUID): Int

    @Modifying
    @Query(
        """
        update Alerta a set a.rearmada = true
        where a.tipo = :tipo and a.productoId = :productoId and a.ubicacionId is null
          and a.cierre = 'MANUAL' and a.rearmada = false
        """,
    )
    fun rearmarSinUbicacion(tipo: TipoAlerta, productoId: UUID): Int
}
