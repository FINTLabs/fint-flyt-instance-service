package no.novari

import no.novari.flyt.audit.config.EnableFlytAuditing
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableFlytAuditing
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
