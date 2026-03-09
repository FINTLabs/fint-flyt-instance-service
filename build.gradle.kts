plugins {
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.ben-manes.versions") version "0.53.0"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.spring") version "2.3.10"
    kotlin("plugin.jpa") version "2.3.10"
}

group = "no.novari"
version = "0.0.1-SNAPSHOT"

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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    compileOnly("org.springframework.security:spring-security-config")
    compileOnly("org.springframework.security:spring-security-web")

    implementation("org.springframework.kafka:spring-kafka")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("no.novari:flyt-kafka:4.0.0")
    implementation("no.novari:flyt-web-resource-server:2.0.0")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.2.3")
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set("1.8.0")
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
