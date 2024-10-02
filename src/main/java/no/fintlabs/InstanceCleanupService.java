package no.fintlabs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstanceCleanupService {

    @Value("${fint.flyt.instance-service.time-to-keep-instance-in-days:60}")
    private int timeToKeepInstancesInDays;

    private final InstanceService instanceService;

    @Scheduled(initialDelay = 1000, fixedDelay = 86400000)
    public void cleanUp() {
        log.info("Cleaning up instances older than {} days", timeToKeepInstancesInDays);
        instanceService.deleteAllOlderThan(timeToKeepInstancesInDays);
    }
}

