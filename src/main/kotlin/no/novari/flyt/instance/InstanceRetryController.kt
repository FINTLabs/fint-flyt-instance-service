package no.novari.flyt.instance

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.EntityNotFoundException
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService
import no.novari.flyt.instance.kafka.InstanceRequestedForRetryEventProducerService
import no.novari.flyt.instance.kafka.InstanceRetryRequestErrorProducerService
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.webresourceserver.UrlPaths.INTERNAL_API
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping(INTERNAL_API)
@Tag(name = "Instance retries", description = "Retry processing for Flyt instances.")
class InstanceRetryController(
    private val instanceService: InstanceService,
    private val instanceFlowHeadersForRegisteredInstanceRequestProducerService:
        InstanceFlowHeadersForRegisteredInstanceRequestProducerService,
    private val instanceRequestedForRetryEventProducerService: InstanceRequestedForRetryEventProducerService,
    private val instanceRetryRequestErrorProducerService: InstanceRetryRequestErrorProducerService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("handlinger/instanser/{instanceId}/prov-igjen")
    @Operation(summary = "Retry an instance", operationId = "retryInstance")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Retry request accepted"),
            ApiResponse(responseCode = "404", description = "Instance not found"),
            ApiResponse(responseCode = "500", description = "Retry request failed"),
        ],
    )
    fun retry(
        @Parameter(description = "The instance identifier")
        @PathVariable instanceId: Long,
    ): ResponseEntity<Void> {
        var instanceFlowHeaders: InstanceFlowHeaders? = null

        try {
            val instance = instanceService.getById(instanceId)

            instanceFlowHeaders = instanceFlowHeadersForRegisteredInstanceRequestProducerService
                .get(instanceId)
                ?.toBuilder()
                ?.correlationId(UUID.randomUUID())
                ?.build()
                ?: throw NoInstanceFlowHeadersException(instanceId)

            instanceRequestedForRetryEventProducerService.publish(instanceFlowHeaders, instance)
            return ResponseEntity.ok().build()
        } catch (_: EntityNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Could not find instance with id='$instanceId'")
        } catch (e: NoInstanceFlowHeadersException) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message)
        } catch (e: Exception) {
            instanceFlowHeaders?.let(instanceRetryRequestErrorProducerService::publishGeneralSystemErrorEvent)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, null, e)
        }
    }

    @PostMapping("handlinger/instanser/prov-igjen/batch")
    @Operation(summary = "Retry multiple instances", operationId = "retryInstances")
    @ApiResponse(responseCode = "200", description = "Batch retry request processed")
    fun retry(
        @RequestBody instanceIds: List<Long>,
    ): ResponseEntity<Void> {
        instanceIds.forEach { instanceId ->
            var instanceFlowHeaders: InstanceFlowHeaders? = null

            try {
                val instance = instanceService.getById(instanceId)

                instanceFlowHeaders = instanceFlowHeadersForRegisteredInstanceRequestProducerService
                    .get(instanceId)
                    ?.toBuilder()
                    ?.correlationId(UUID.randomUUID())
                    ?.build()
                    ?: throw NoInstanceFlowHeadersException(instanceId)

                instanceRequestedForRetryEventProducerService.publish(instanceFlowHeaders, instance)
            } catch (_: EntityNotFoundException) {
                log.error("Could not find instance with id='{}'", instanceId)
            } catch (e: NoInstanceFlowHeadersException) {
                log.error(e.message)
            } catch (e: Exception) {
                instanceFlowHeaders?.let(instanceRetryRequestErrorProducerService::publishGeneralSystemErrorEvent)
                log.error(e.message)
            }
        }

        return ResponseEntity.ok().build()
    }
}
