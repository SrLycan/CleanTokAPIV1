package ec.cleantok.inventario.dominio

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface StockRepository : JpaRepository<Stock, UUID> {
    /** Crea la fila en 0 si no existe; no hace nada si ya existe (seguro ante concurrencia). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        insert into stock (id, empresa_id, establecimiento_id, producto_id, ubicacion_id, cantidad, version)
        values (:id, :empresaId, :establecimientoId, :productoId, :ubicacionId, 0, 0)
        on conflict (producto_id, ubicacion_id) do nothing
        """,
    )
    fun asegurar(id: UUID, empresaId: UUID, establecimientoId: UUID, productoId: UUID, ubicacionId: UUID): Int

    /** SELECT … FOR UPDATE: serializa los movimientos concurrentes sobre el mismo saldo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productoId = :productoId and s.ubicacionId = :ubicacionId")
    fun bloquear(productoId: UUID, ubicacionId: UUID): Stock

    fun findByProductoIdAndUbicacionId(productoId: UUID, ubicacionId: UUID): Stock?

    fun findAllByEstablecimientoId(establecimientoId: UUID): List<Stock>

    fun findAllByUbicacionId(ubicacionId: UUID): List<Stock>

    fun findAllByProductoId(productoId: UUID): List<Stock>
}

interface MovimientoRepository : JpaRepository<Movimiento, UUID>, JpaSpecificationExecutor<Movimiento> {
    fun findByClientUuid(clientUuid: UUID): Movimiento?

    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Movimiento?

    /** Último traslado hacia esa ubicación (base del umbral para sugerir el reporte de nivel). */
    fun findFirstByProductoIdAndUbicacionDestinoIdAndTipoOrderBySecuenciaDesc(
        productoId: UUID,
        ubicacionDestinoId: UUID,
        tipo: TipoMovimiento,
    ): Movimiento?

    fun existsByProductoId(productoId: UUID): Boolean

    fun existsByProductoIdAndTipo(productoId: UUID, tipo: TipoMovimiento): Boolean

    /** ¿Llegó producto a esa ubicación después de cierto momento? (cierra NIVEL_CRITICO). */
    fun existsByProductoIdAndUbicacionDestinoIdAndTipoInAndOcurridoEnAfter(
        productoId: UUID,
        ubicacionDestinoId: UUID,
        tipos: Collection<TipoMovimiento>,
        despues: java.time.Instant,
    ): Boolean
}
