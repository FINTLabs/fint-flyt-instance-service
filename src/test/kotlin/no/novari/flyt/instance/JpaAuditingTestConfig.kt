package no.novari.flyt.instance

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@TestConfiguration
@EnableJpaAuditing(auditorAwareRef = "flytAuditorAware")
class JpaAuditingTestConfig
