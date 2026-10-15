package ec.cleantok.identidad.dominio

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface EmpresaRepository : JpaRepository<Empresa, UUID> {
    fun existsByRuc(ruc: String): Boolean

    fun findByRuc(ruc: String): Empresa?
}

interface ProveedorRepository : JpaRepository<Proveedor, UUID> {
    fun existsByRuc(ruc: String): Boolean
}

interface UsuarioRepository : JpaRepository<Usuario, UUID> {
    fun findAllByProveedorIdAndRolAndActivoTrueOrderByNombre(proveedorId: UUID, rol: Rol): List<Usuario>

    @Query("select u from Usuario u where lower(u.email) = lower(:email)")
    fun buscarPorEmail(email: String): Usuario?

    @Query("select count(u) > 0 from Usuario u where lower(u.email) = lower(:email)")
    fun existeEmail(email: String): Boolean

    fun findByIdAndEmpresaId(id: UUID, empresaId: UUID): Usuario?

    fun findAllByEmpresaId(empresaId: UUID, pageable: Pageable): Page<Usuario>

    fun findAllByProveedorIdOrderByNombre(proveedorId: UUID): List<Usuario>

    fun countByEmpresaIdAndRolAndActivoTrue(empresaId: UUID, rol: Rol): Long

    fun countByProveedorIdAndRolAndActivoTrue(proveedorId: UUID, rol: Rol): Long

    fun countByEmpresaId(empresaId: UUID): Long

    fun countByProveedorId(proveedorId: UUID): Long
}

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    /** Bloquea la fila: dos refresh simultáneos con el mismo token no pueden rotarlo dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTokenHash(tokenHash: String): RefreshToken?

    @Modifying
    @Query("update RefreshToken t set t.revocadoEn = :ahora where t.familiaId = :familiaId and t.revocadoEn is null")
    fun revocarFamilia(familiaId: UUID, ahora: Instant): Int

    @Modifying
    @Query("update RefreshToken t set t.revocadoEn = :ahora where t.usuarioId = :usuarioId and t.revocadoEn is null")
    fun revocarDeUsuario(usuarioId: UUID, ahora: Instant): Int
}
