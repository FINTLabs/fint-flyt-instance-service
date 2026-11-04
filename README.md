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

### OpenAPI Documentation

Swagger UI and the generated OpenAPI specification are available only through direct service access, such as a
Kubernetes port-forward. Their paths sit outside the external ingress route for `/api/intern/handlinger/instanser`:

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`
- OpenAPI YAML: `/v3/api-docs.yaml`

For the FINTLabs beta deployment:

```shell
kubectl -n fintlabs-no port-forward service/fint-flyt-instance-service 8080:8080
```

Swagger UI is then available at `http://localhost:8080/beta/fintlabs-no/swagger-ui.html`, and OpenAPI JSON at
`http://localhost:8080/beta/fintlabs-no/v3/api-docs`.

## Kafka Integration

- Produces the following topics via the FINT Kafka template services:
  - `instance-requested-for-retry`
  - `instance-registered`, `instance-registration-error`, `instance-deleted`, and `instance-retry-request-error`
- Consumes instance lifecycle events through `InstanceReceivedEventConsumerConfiguration` and `InstanceDispatchedConsumerConfiguration` to coordinate retries and header lookups.
- Topic retention is governed by `novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time` (default four days). A millisecond suffix variant (`...-retention-time-ms`) is also supported for services that require explicit units.

### Instance-received progress and handover

Instance-service writes `next_offset` per topic/partition to the existing
`<history-schema>.instance_received_offset` table in the shared PostgreSQL database. History-service
owns the V9/V10 migrations. `HISTORY_DATABASE_SCHEMA` explicitly selects the history schema in every
overlay; instance entity mappings and Flyway remain in the instance schema.

Missing rows are initialized from the existing consumer group's committed position, including idle
partitions. Existing progress is preserved. A missing group position is an error; there is no automatic
earliest/latest fallback. Initialization reflects the legacy group's previous processing policy.

After saving, registration waits for the library producer's broker confirmation before checkpointing.
If saving fails, the existing `instance-error` event is published instead. Failed publication of
`instance-registered` is retried with the same saved instance and generated ID; it is not converted
into a registration error. Processing continues after publication is confirmed and the position is
stored. Completed records are skipped on redelivery.
One record is polled at a time, with auto-commit disabled; `max.poll.interval.ms` must accommodate
producer delivery and database work.

Null or unreadable payloads produce the existing registration error when flow headers can be read.
Unreadable headers are logged using record coordinates, without fabricating history. Invalid messages
are skipped automatically: the database position is advanced before Kafka seeks and commits the next
position. Manual edits of either database or Kafka offsets are not needed for malformed input.

If publishing either event or storing the position fails, the consumer retries automatically with a
five-second backoff. It retains the saved result and confirmed publication in memory for the active
assignment, so retries do not save the instance or repeat a confirmed publication. Polling and temporary
assignment failures also retry; no manual restart is needed after connectivity or privileges recover.
Position and Kafka commits remain unchanged until the pending work completes. The retry handler logs
the original cause on the first failure and when the underlying exception type changes.
Repeated failures of the same type on a consumer thread produce at most one warning per
minute, without a stack trace. Successful processing after retry is logged once. Invalid message
content is not explicitly written to logs.

Saving, sending, and checkpointing have separate transactions. The temporary state is discarded on
partition revocation or loss. Crashes, rebalances and ambiguous publication outcomes can still cause
duplicates; this is not a durable outbox or an exactly-once guarantee.

`cluster_id` and `topic_id` stay NULL until handover. Instance-service refuses processing once
history-service has assigned identity. Missing or out-of-range starting positions also stop processing
rather than guessing an offset. Stop and drain the old writer before binding and starting the new owner.
Fatal stops are exposed as `DOWN` by the Kafka library's existing `kafkaConsumers` health component
and aggregate `/actuator/health`. This is a health signal, not an external alert or a guarantee that
liveness triggers a restart. Probe configuration is unchanged.

#### Database permissions

Grant privileges before deploying the writer. Run the following in the shared `fint-flyt` database as
an object owner or administrator with grant privileges. Replace `INSTANCE_SERVICE_DB_ROLE` with the
actual role from instance-service's database credentials; do not assume the role equals the schema name.

```sql
GRANT USAGE
ON SCHEMA afk_no_fint_flyt_history_service_db
TO "INSTANCE_SERVICE_DB_ROLE";

GRANT SELECT, INSERT, UPDATE
ON TABLE afk_no_fint_flyt_history_service_db.instance_received_offset
TO "INSTANCE_SERVICE_DB_ROLE";
```

Use the corresponding history schema and instance-service role for every organization and environment.
No sequence, CREATE, DELETE, or schema ownership privileges are required. Check the grants while
connected as instance-service's role:

```sql
SELECT current_user;

SELECT
    has_schema_privilege(current_user, 'afk_no_fint_flyt_history_service_db', 'USAGE') AS schema_usage,
    has_table_privilege(current_user, 'afk_no_fint_flyt_history_service_db.instance_received_offset', 'SELECT') AS table_select,
    has_table_privilege(current_user, 'afk_no_fint_flyt_history_service_db.instance_received_offset', 'INSERT') AS table_insert,
    has_table_privilege(current_user, 'afk_no_fint_flyt_history_service_db.instance_received_offset', 'UPDATE') AS table_update;
```

All four privilege checks must return true. Missing tables or privileges prevent progress and produce
retries; checkpointing is never silently disabled.

#### Transition to history-service

1. Apply the history-service migrations and the grants above before deploying this writer. Keep
   history-service's instance functionality disabled.
2. Deploy instance-service and verify a row for every received-event partition and advancing progress
   when messages are processed. Resolve any consumer stop before proceeding.
3. Stop instance-service's received-event processing and drain in-flight work. Shutdown must allow the
   configured producer delivery and checkpoint transactions to finish; do not capture a final position
   from a forcibly terminated process with unresolved work.
4. Have history-service validate the stored positions against broker boundaries and assign the real
   cluster/topic identities using its existing position service. Start it as the only processing owner.

Normal handover does not require manually editing this table or resetting Kafka group offsets.
After identity binding, restarting the legacy received-event consumer is rejected; rollback requires
a verified transition rather than blindly starting the old deployment.

## Scheduled Tasks

`InstanceCleanupService.cleanUp()` runs every 24 hours (initial delay 30 seconds) and deletes instances older than `novari.flyt.instance-service.time-to-keep-instance-in-days` (defaults to 60). When a record is removed the service publishes `instance-deleted` events.

## Configuration

The application composes the shared Spring profiles `flyt-kafka`, `flyt-logging`, `flyt-postgres`, and `flyt-web-resource-server`.

Key properties:

| Property                                                                             | Description                                                                                 |
|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `fint.application-id`                                                                | Defaults to `fint-flyt-instance-service`.                                                   |
| `novari.flyt.instance-service.time-to-keep-instance-in-days`                         | Retention window used by the cleanup job (default 60).                                      |
| `novari.flyt.instance-service.database.history-schema`                               | Explicit existing history schema, supplied by `HISTORY_DATABASE_SCHEMA`.                    |
| `novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time` | Duration string used to configure Kafka topic retention (default `4d`).                     |
| `spring.datasource.*`                                                                | Provide JDBC connection details for Postgres; overlays inject environment-specific secrets. |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri`                               | OAuth issuer for protected internal endpoints.                                              |
| `novari.flyt.web-resource-server.security.api.internal-client`                       | Defines clients permitted to call internal APIs.                                            |

Secrets referenced in Kustomize overlays must provide database credentials, OAuth settings, and Kafka access.

## Running Locally

Prerequisites:

- Java 25
- Docker (for the bundled Postgres and Kafka environment)
- Gradle (wrapper included)

`docker-compose.yaml` provides local Postgres on `localhost:5433` (including the `fintlabs_no` instance schema) and a single-node Kafka broker on `localhost:9092`. Both match the settings in `application-local-staging.yaml`. The additional history-table prerequisites are described below.

```shell
docker compose up -d                                          # start Postgres and Kafka
HISTORY_DATABASE_SCHEMA=fintlabs_no_fint_flyt_history_service_db ./gradlew bootRun --args='--spring.profiles.active=local-staging'
```

The service starts on `localhost:8081`, runs its Flyway migrations against the `fintlabs_no` schema and creates its Kafka topics on startup.

Received-event processing additionally requires the history offset table in that same local database
and an established group position as described above. The instance-service Compose setup does not
apply history-service migrations or choose initial offsets for a fresh broker. The cross-schema
PostgreSQL and Kafka integration tests supply isolated fixtures for this transition behavior.

```shell
docker compose down     # stop the environment; topics and database are empty on the next start
```

Add `--profile tools` to `docker compose up` to also start Kafdrop on http://localhost:19000.

Other useful commands:

```shell
./gradlew check           # compile, run tests and ktlint
./gradlew test            # run tests only
```

Override properties (e.g. `novari.flyt.instance-service.time-to-keep-instance-in-days`) as needed for local experiments.

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
- Restricts internal APIs to trusted clients defined via `novari.flyt.web-resource-server.security.api.internal-client`.

## Observability & Operations

- Aggregate health: `/actuator/health`, including Kafka consumer status (`kafkaConsumers`).
- Readiness and liveness probes: `/actuator/health/readiness` and `/actuator/health/liveness`.
- Metrics: `/actuator/prometheus`.
- Structured logging via shared FINT logging profile; Kafka and cleanup events emit detailed log messages for traceability.

## Development Tips

- When introducing new instance states, ensure the corresponding Kafka producers/consumers are updated and retention properties remain valid.
- `InstanceService.deleteAllOlderThan` publishes deletion events extending tests when altering cleanup semantics.
- If additional organizations require custom role mappings for overlays, update `script/render-overlay.sh` to reflect the new rules.

## Dependency Locking

The build uses [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html), so every build resolves exactly the same direct and transitive versions. The locked versions live in the committed `gradle.lockfile` files. A pull request therefore shows every transitive version change it causes. Gradle plugins are not locked, because Dependabot only updates `gradle.lockfile`; their versions are pinned in the `plugins` block instead.

**Automatic (Dependabot):** Dependabot updates the lockfiles in its own pull requests, so no manual step is needed. If two Dependabot pull requests conflict in a lockfile, Dependabot rebases the remaining one after the first is merged. If it does not, comment `@dependabot recreate` on the pull request.

**Manual:** Any change you make by hand that affects dependency resolution requires regenerating the lockfiles. That covers versions, `extra[...]` properties, constraints, and adding or removing a dependency:

```bash
./gradlew dependencies --write-locks
```

Commit the updated lockfiles together with the change. If you forget, the build fails both locally and in CI with an error saying the resolved version "is not part of the dependency lock state".

## Contributing

1. Create a topic branch for your change.
2. Run `./gradlew check` before raising a PR.
3. If you modify Kustomize templates or overlay logic, rerun `./script/render-overlay.sh` and commit the generated manifests.
4. Add or adjust tests for any new behaviour or edge cases.

FINT Flyt Instance Service is maintained by the FINT Flyt team. Reach out via the internal Slack channel or open an issue in this repository for questions or enhancements.
