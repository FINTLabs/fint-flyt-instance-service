buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
        constraints {
            classpath("org.apache.httpcomponents.client5:httpclient5:5.6.4")
            classpath("org.apache.httpcomponents.core5:httpcore5:5.4.4")
            classpath("org.apache.httpcomponents.core5:httpcore5-h2:5.4.4")
            classpath("org.apache.commons:commons-lang3:3.21.0")
        }
    }
}

plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.github.ben-manes.versions") version "0.64.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    kotlin("plugin.jpa") version "2.4.10"
}

group = "no.novari"
version = "0.0.1-SNAPSHOT"

var springdocOpenApiVersion = "2.9.1"

kotlin {
    jvmToolchain(25)
}

configurations {
    compileOnly
}

tasks.jar {
    isEnabled = false
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.fintlabs.no/releases")
    }
    mavenLocal()
}

extra["commons-lang3.version"] = "3.21.0"
extra["jackson-bom.version"] = "2.22.3"
extra["log4j2.version"] = "2.26.1"
extra["postgresql.version"] = "42.7.12"
extra["tomcat.version"] = "10.1.59"

dependencies {
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.12.0") {
            because("Fixes CVE-2026-59949 in the kafka-clients transitive dependency")
        }
        testImplementation("org.apache.commons:commons-compress:1.28.0") {
            because("Fixes CVE-2024-25710 and CVE-2024-26308 in the Testcontainers transitive dependency")
        }
    }

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocOpenApiVersion")
    compileOnly("org.springframework.security:spring-security-config")
    compileOnly("org.springframework.security:spring-security-web")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("no.novari:flyt-audit-starter:1.2.0")
    implementation("no.novari:flyt-kafka:7.3.0")
    implementation("no.novari:flyt-web-resource-server:4.0.0")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // Pinned to 8.x: 9.x pulls in Jackson 3 (tools.jackson), while Spring Boot 3.5 uses Jackson 2
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.test {
    useJUnitPlatform()
}

springBoot {
    mainClass.set("no.novari.ApplicationKt")
}

ktlint {
    version.set("1.8.0")
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
