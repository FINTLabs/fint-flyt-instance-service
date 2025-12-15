# FINT Flyt Instance Service

Spring Boot service that persists FINT Flyt process instances, orchestrates Kafka events, and exposes internal APIs for retrying and cleaning up instance state. It coordinates with downstream integrations, and maintains retention policies.

## Highlights

- **Transactional REST API** — internal controller secures retry endpoints under `/api/intern/handlinger/instanser`.
- **Kafka orchestration** — produces retry/deletion events and consumes lifecycle notifications to keep state in sync.
- **Scheduled hygiene** — daily cleanup removes stale instances according to a configurable retention window.
- **Postgres persistence** — stores instance payloads with JPA mappings for retrieval, batching, and deletion.

## Architecture Overview

| Component                                                     | Responsibility                                                                                                    |
|---------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `InstanceRetryController`                                     | Handles single/batch retry requests and triggers Kafka events; lives under the internal API namespace.            |
| `InstanceService`                                             | Core business logic for CRUD operations, mapping DTOs, and coordinating downstream producers.                     |
| `InstanceRepository`                                          | Spring Data JPA repository that persists and queries instance entities in Postgres.                               |
| `InstanceCleanupService`                                      | Scheduled service that deletes instances older than `novari.flyt.instance-service.time-to-keep-instance-in-days`. |
| `InstanceFlowHeadersForRegisteredInstanceRequestProducerService` | Requests flow headers used when emitting retry/deleted events.                                                    |
| `InstanceRequestedForRetryEventProducerService`               | Publishes instance retry requests to Kafka and provisions the topic with retention configuration.                 |
| `InstanceDeletedEventProducerService`                         | Emits `instance-deleted` events whenever records are purged.                                                      |
| `InstanceRetryRequestErrorEventProducerService`               | Publishes general system errors when retries fail after headers were resolved.                                    |
| `InstanceReceivedEventConsumerConfiguration` / `InstanceDispatchedConsumerConfiguration` | Configures Kafka consumers that react to lifecycle events to stay aligned with upstream flows.                    |

## HTTP API

Base path: `/api/intern/handlinger/instanser`

| Method | Path                                      | Description                                                  | Request body                               | Response                          |
|--------|-------------------------------------------|--------------------------------------------------------------|--------------------------------------------|-----------------------------------|
| `POST` | `/{instanceId}/prov-igjen`                | Retry a specific instance by ID.                             | –                                          | `200 OK` or `404/500` on failure. |
| `POST` | `/prov-igjen/batch`                       | Retry multiple instances in one request.                     | JSON array of instance IDs (`List<Long>`). | `200 OK`; logs per-item failures. |

Errors surface as standard Spring MVC responses: `404 Not Found` when an instance is missing, `500 Internal Server Error` for unexpected issues.

## Kafka Integration

- Produces the following topics via the FINT Kafka template services:
  - `instance-requested-for-retry`
  - `instance-registered`, `instance-registration-error`, `instance-deleted`, and `instance-retry-request-error`
- Consumes instance lifecycle events through `InstanceReceivedEventConsumerConfiguration` and `InstanceDispatchedConsumerConfiguration` to coordinate retries and header lookups.
- Topic retention is governed by `novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time` (default four days). A millisecond suffix variant (`...-retention-time-ms`) is also supported for services that require explicit units.

## Scheduled Tasks

`InstanceCleanupService.cleanUp()` runs every 24 hours (initial delay 30 seconds) and deletes instances older than `novari.flyt.instance-service.time-to-keep-instance-in-days` (defaults to 60). When a record is removed the service publishes `instance-deleted` events.

## Configuration

The application composes the shared Spring profiles `flyt-kafka`, `flyt-logging`, `flyt-postgres`, and `flyt-resource-server`.

Key properties:

| Property                                                                             | Description |
|--------------------------------------------------------------------------------------| --- |
| `fint.application-id`                                                                | Defaults to `fint-flyt-instance-service`. |
| `novari.flyt.instance-service.time-to-keep-instance-in-days`                         | Retention window used by the cleanup job (default 60). |
| `novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time` | Duration string used to configure Kafka topic retention (default `4d`). |
| `spring.datasource.*`                                                                | Provide JDBC connection details for Postgres; overlays inject environment-specific secrets. |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri`                               | OAuth issuer for protected internal endpoints. |
| `novari.flyt.resource-server.security.api.internal-client`                           | Defines clients permitted to call internal APIs. |

Secrets referenced in Kustomize overlays must provide database credentials, OAuth settings, and Kafka access.

## Running Locally

Prerequisites:

- Java 21+
- Docker (for the bundled Postgres helper) and access to a Kafka cluster
- Gradle (wrapper included)

Useful commands:

```shell
./start-postgres          # launch a disposable Postgres on localhost:5433
./gradlew clean build     # compile and run tests
./gradlew bootRun         # start the service with default profiles
./gradlew test            # run unit tests
```

Configure `SPRING_PROFILES_ACTIVE` or override properties (e.g. `novari.flyt.instance-service.time-to-keep-instance-in-days`) as needed for local experiments. Point Kafka settings at your development broker (typically `localhost:9092`).

## Deployment

Kustomize layout:

- `kustomize/base/` contains the shared `Application` resource and supporting Kubernetes manifests.
- `kustomize/overlays/<org>/<env>/` applies namespace labels, Kafka ACLs, and URL prefixes references per organization and environment.

Templates are centralized in `kustomize/templates/`:

- `overlay.yaml.tpl` — envsubst template used for every overlay.

Regenerate overlays after changing the template or rendering logic:

```shell
./script/render-overlay.sh
```

The script injects namespace-specific values (base paths, Kafka topics, role mappings) and rewrites each `kustomization.yaml` in place.

## Security

- Uses the FINT OAuth2 resource server setup for JWT validation (`spring.security.oauth2.resourceserver.jwt.issuer-uri`).
- Restricts internal APIs to trusted clients defined via `novari.flyt.resource-server.security.api.internal-client`.

## Observability & Operations

- Readiness and liveness: `/actuator/health`.
- Metrics: `/actuator/prometheus`.
- Structured logging via shared FINT logging profile; Kafka and cleanup events emit detailed log messages for traceability.

## Development Tips

- When introducing new instance states, ensure the corresponding Kafka producers/consumers are updated and retention properties remain valid.
- `InstanceService.deleteAllOlderThan` publishes deletion events extending tests when altering cleanup semantics.
- If additional organizations require custom role mappings for overlays, update `script/render-overlay.sh` to reflect the new rules.

## Contributing

1. Create a topic branch for your change.
2. Run `./gradlew test` (and additional checks) before raising a PR.
3. If you modify Kustomize templates or overlay logic, rerun `./script/render-overlay.sh` and commit the generated manifests.
4. Add or adjust tests for any new behaviour or edge cases.

FINT Flyt Instance Service is maintained by the FINT Flyt team. Reach out via the internal Slack channel or open an issue in this repository for questions or enhancements.
