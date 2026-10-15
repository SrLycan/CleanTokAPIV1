package ec.cleantok.comun.entidad

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Transient
import org.hibernate.Hibernate
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * Base de todas las entidades: id UUID asignado en la aplicación y marcas de tiempo en UTC.
 * Implementa [Persistable] para que `save()` haga INSERT directo sin un SELECT previo.
 */
@MappedSuperclass
abstract class EntidadBase : Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private var id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
        protected set

    @Transient
    private var nuevo: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = nuevo

    @PrePersist
    protected fun alPersistir() {
        val ahora = Instant.now()
        createdAt = ahora
        updatedAt = ahora
    }

    @PreUpdate
    protected fun alActualizar() {
        updatedAt = Instant.now()
    }

    @PostLoad
    @PostPersist
    protected fun marcarExistente() {
        nuevo = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntidadBase) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == other.getId()
    }

    override fun hashCode(): Int = id.hashCode()
}
