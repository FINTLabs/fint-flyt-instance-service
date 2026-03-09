package no.novari.flyt.instance

import no.novari.flyt.instance.kafka.InstanceDeletedEventProducerService
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService
import no.novari.flyt.instance.model.InstanceMappingService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import org.slf4j.LoggerFactory
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.LocalDateTime

@Service
class InstanceService(
    private val instanceRepository: InstanceRepository,
    private val instanceMappingService: InstanceMappingService,
    private val instanceDeletedEventProducerService: InstanceDeletedEventProducerService,
    private val instanceFlowHeadersForRegisteredInstanceRequestProducerService:
        InstanceFlowHeadersForRegisteredInstanceRequestProducerService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun save(instanceObjectDto: InstanceObjectDto): InstanceObjectDto {
        return instanceMappingService.toInstanceObjectDto(
            instanceRepository.save(instanceMappingService.toInstanceObject(instanceObjectDto)),
        )
    }

    fun getById(instanceId: Long): InstanceObjectDto {
        return instanceMappingService.toInstanceObjectDto(instanceRepository.getReferenceById(instanceId))
    }

    fun getAllOlderThan(days: Int): List<InstanceObjectDto> {
        val thresholdDate = LocalDateTime.now().minusDays(days.toLong())
        val timestamp = Timestamp.valueOf(thresholdDate)

        return instanceRepository
            .findAllOlderThan(timestamp)
            .map(instanceMappingService::toInstanceObjectDto)
    }

    fun deleteAllOlderThan(days: Int) {
        getAllOlderThan(days).forEach { instance ->
            val instanceId = instance.id
            if (instanceId == null) {
                log.warn("Instance without id encountered during cleanup")
                return@forEach
            }

            instanceFlowHeadersForRegisteredInstanceRequestProducerService
                .get(instanceId)
                ?.let(instanceDeletedEventProducerService::publish)
                ?: log.warn("No instance flow headers found for instance with id={}", instanceId)

            try {
                instanceRepository.deleteById(instanceId)
                log.info("Instance with id={} deleted", instanceId)
            } catch (_: EmptyResultDataAccessException) {
                log.warn("Instance with id={} was already deleted", instanceId)
            } catch (e: Exception) {
                log.error("Failed to delete instance with id={}", instanceId, e)
            }
        }
    }

    fun deleteInstanceByInstanceFlowHeaders(instanceFlowHeaders: InstanceFlowHeaders) {
        instanceRepository.deleteById(instanceFlowHeaders.instanceId)
        instanceDeletedEventProducerService.publish(instanceFlowHeaders)
    }
}
