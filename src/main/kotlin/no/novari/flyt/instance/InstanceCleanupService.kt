package no.novari.flyt.instance

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class InstanceCleanupService(
    @param:Value("\${novari.flyt.instance-service.time-to-keep-instance-in-days:60}")
    private val timeToKeepInstancesInDays: Int,
    private val instanceService: InstanceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 30000, fixedDelay = 86400000)
    fun cleanUp() {
        log.info("Cleaning up instances older than {} days", timeToKeepInstancesInDays)
        instanceService.deleteAllOlderThan(timeToKeepInstancesInDays)
    }
}
