package no.novari.flyt.instance

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.kafka.InstanceDeletedEventProducerService
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService
import no.novari.flyt.instance.model.InstanceMappingService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class InstanceService(
    private val instanceRepository: InstanceRepository,
    private val instanceMappingService: InstanceMappingService,
    private val instanceDeletedEventProducerService: InstanceDeletedEventProducerService,
    private val instanceFlowHeadersForRegisteredInstanceRequestProducerService:
        InstanceFlowHeadersForRegisteredInstanceRequestProducerService,
) {
    private val log = KotlinLogging.logger {}

    fun save(instanceObjectDto: InstanceObjectDto): InstanceObjectDto {
        return instanceMappingService.toInstanceObjectDto(
            instanceRepository.save(instanceMappingService.toInstanceObject(instanceObjectDto)),
        )
    }

    fun getById(instanceId: Long): InstanceObjectDto {
        return instanceMappingService.toInstanceObjectDto(instanceRepository.getReferenceById(instanceId))
    }

    fun getAllOlderThan(days: Int): List<InstanceObjectDto> {
        val thresholdDate = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)

        return instanceRepository
            .findAllOlderThan(thresholdDate)
            .map(instanceMappingService::toInstanceObjectDto)
    }

    fun deleteAllOlderThan(days: Int) {
        getAllOlderThan(days).forEach { instance ->
            val instanceId = instance.id
            if (instanceId == null) {
                log.atWarn { message = "Instance without id encountered during cleanup" }
                return@forEach
            }

            instanceFlowHeadersForRegisteredInstanceRequestProducerService
                .get(instanceId)
                ?.let(instanceDeletedEventProducerService::publish)
                ?: log.atWarn {
                    message = "No instance flow headers found for instance with id={}"
                    arguments = arrayOf(instanceId)
                }

            try {
                instanceRepository.deleteById(instanceId)
                log.atInfo {
                    message = "Instance with id={} deleted"
                    arguments = arrayOf(instanceId)
                }
            } catch (_: EmptyResultDataAccessException) {
                log.atWarn {
                    message = "Instance with id={} was already deleted"
                    arguments = arrayOf(instanceId)
                }
            } catch (e: Exception) {
                log.atError {
                    message = "Failed to delete instance with id={}"
                    arguments = arrayOf(instanceId)
                    cause = e
                }
            }
        }
    }

    fun deleteInstanceByInstanceFlowHeaders(instanceFlowHeaders: InstanceFlowHeaders) {
        instanceRepository.deleteById(instanceFlowHeaders.instanceId)
        instanceDeletedEventProducerService.publish(instanceFlowHeaders)
    }
}
