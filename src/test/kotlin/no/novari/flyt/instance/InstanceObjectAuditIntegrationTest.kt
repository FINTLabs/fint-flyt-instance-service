package no.novari.flyt.instance

import jakarta.persistence.EntityManager
import no.novari.flyt.audit.actor.Actor
import no.novari.flyt.instance.model.entities.InstanceObject
import no.novari.flyt.instance.model.entities.InstanceObjectCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JpaAuditingTestConfig::class)
class InstanceObjectAuditIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `sets system as auditor on root and cascaded children`() {
        val child = InstanceObject(valuePerKey = mutableMapOf("childKey" to "childValue"))
        val root =
            InstanceObject(
                valuePerKey = mutableMapOf("rootKey" to "rootValue"),
                objectCollectionPerKey =
                    mutableMapOf(
                        "children" to InstanceObjectCollection(objects = mutableListOf(child)),
                    ),
            )

        val saved = instanceRepository.saveAndFlush(root)

        assertNotNull(saved.createdAt)
        assertEquals(Actor.System, saved.createdBy)

        val savedChildren = saved.objectCollectionPerKey.getValue("children")
        val savedChild = savedChildren.objects.single()
        assertNotNull(savedChild.createdAt)
        assertEquals(Actor.System, savedChild.createdBy)
    }

    @Test
    fun `persists created by as jsonb`() {
        val saved = instanceRepository.saveAndFlush(InstanceObject())

        val createdBy =
            entityManager
                .createNativeQuery("select created_by ->> 'type' from instance_object where id = :id")
                .setParameter("id", saved.id)
                .singleResult

        assertEquals("SYSTEM", createdBy)
    }

    @Test
    fun `finds only instances created before threshold`() {
        val instance = instanceRepository.saveAndFlush(InstanceObject())

        entityManager
            .createNativeQuery("update instance_object set created_at = :createdAt where id = :id")
            .setParameter("createdAt", Instant.now().minus(10, ChronoUnit.DAYS))
            .setParameter("id", instance.id)
            .executeUpdate()

        val recent = instanceRepository.saveAndFlush(InstanceObject())
        entityManager.clear()

        val result = instanceRepository.findAllOlderThan(Instant.now().minus(5, ChronoUnit.DAYS))

        assertEquals(listOf(instance.id), result.map { it.id })
        assertNotNull(recent.id)
    }
}
