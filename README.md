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

Instance-service writes one position per `instance-received` topic and partition to
`<history-schema>.instance_received_offset`. The table belongs to history-service and must already
exist in the same PostgreSQL database. Its V9 and V10 migrations remain owned by history-service;
instance-service neither creates the table nor changes its own Flyway schema.

Configure `novari.flyt.instance-service.database.history-schema` through `HISTORY_DATABASE_SCHEMA`.
Every deployment overlay supplies an explicit organization-specific value, including beta. For AFK
the value is `afk_no_fint_flyt_history_service_db`. The schema is validated and quoted explicitly;
the datasource and instance entity mappings are unchanged.

The received-event consumer keeps its existing application-default group. On partition assignment,
a missing row is initialized from that group's committed Kafka offset, without adding one. This
also initializes idle partitions. Existing rows are preserved. If both the row and committed Kafka
position are absent, consumption stops; there is no automatic earliest/latest fallback, seek, or
Kafka group-offset reset. The initial committed position reflects the legacy consumer's previous
behavior, including its former skip-failed-records policy; it does not repair past processing failures.

After saving an instance, the consumer waits for the existing library producer's `instance-registered`
publication to be confirmed by the broker. If saving fails or produces no ID, it waits for confirmation
of the existing registration error instead. Only then does a short database transaction store the
record's offset plus one and update `updated_at`. Kafka sending and waiting take place outside database
transactions. The producer futures use the library's delivery settings; there is no additional short
acknowledgement timeout. This waiting is specific to registration, not the manual retry API.

The consumer polls one record at a time and commits Kafka offsets after each successfully handled
record, with auto-commit disabled. Ensure `max.poll.interval.ms` accommodates the producer's
`max.block.ms`, `delivery.timeout.ms`, and database work. Already completed records below the stored
position are skipped, and database progress never moves backwards. Kafka offsets may contain gaps.
Publication failures, checkpoint failures, unreadable input, or assignment failures stop this consumer
instead of skipping the record. Later records cannot advance the position past that failure. Other
consumers and REST endpoints retain their existing behavior. Error logs contain record coordinates
and sanitized technical causes, without the payload.

Instance-service leaves `cluster_id` and `topic_id` NULL. A row with either identity assigned is
considered taken over by history-service: instance-service refuses further processing and writes.
Stop and drain instance-service before history-service assigns these identities. This check does not
replace the requirement for a single processing owner during the transition.

This is a progress checkpoint, not a distributed transaction or outbox. A crash between saving,
publishing, and checkpointing can still cause redelivery and duplicate instances or events. Restart
does not automatically retry a saved-but-unpublished instance using its original ID. Before handover,
resolve any stopped processing and verify that no publication or database update is outstanding.

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

All four privilege checks must return true. Missing tables or privileges stop received-event processing;
the application does not silently disable checkpointing.

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

- Readiness and liveness: `/actuator/health`.
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
