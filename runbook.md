# Lego Data Ingress Runbook

This runbook documents the operational surface of `lego-data-ingress`: controllers, scheduled jobs, event-driven ingestion flows, configuration settings, rollout switches, logging, metrics, and validation steps.

The service defaults to the `local,sandbox` profiles unless overridden by `spring.profiles.active`. Runtime OpenAPI is available at `/swagger-ui/index.html` and `/v3/api-docs` when the service is running.

## Contents

- [Service Scope](#service-scope)
- [Profiles And External Config](#profiles-and-external-config)
- [REST Controllers](#rest-controllers)
- [Scheduled Jobs](#scheduled-jobs)
- [Kafka And S3 Event Pipelines](#kafka-and-s3-event-pipelines)
- [Configuration Reference](#configuration-reference)
- [Readiness And Actuator](#readiness-and-actuator)
- [Metrics](#metrics)
- [Structured Logs](#structured-logs)
- [Rollout Procedures](#rollout-procedures)
- [Operational Checks](#operational-checks)

## Service Scope

`lego-data-ingress` handles these operational workflows:

**Photo ingestion**

- Mode: Kafka/S3 event driven
- Purpose: Process uploaded MinIO photo objects into durable DB photo metadata and object-storage state.

**Photo delete reconciliation**

- Mode: Kafka/S3 event driven
- Purpose: React to final photo object removals.

**BrickLink catalog ingestion**

- Mode: Kafka/S3 event driven
- Purpose: Parse BrickLink XML catalog/category files and upsert external catalog/category tables.

**Rebrickable catalog ingestion**

- Mode: Kafka/S3 event driven
- Purpose: Parse Rebrickable gzipped CSV catalog/theme files and upsert external catalog/category tables.

**Image-hosting sync**

- Mode: REST and scheduled job
- Purpose: Reconcile item inventory photos and albums to Flickr through `lego-imaging`.

**Image-hosting repair**

- Mode: REST
- Purpose: Repair DB links from current remote image-hosting state.

**BrickLink order sync**

- Mode: Scheduled job
- Purpose: Poll BrickLink open orders and, when apply mode is enabled, sync marketplace order staging tables.


## Profiles And External Config

### Default Profiles

`src/main/resources/application.yml` sets:

```yaml
spring:
  profiles:
    default: local,sandbox
```

If no profile is supplied, Spring loads `application.yml`, `application-local.yml`, and `application-sandbox.yml`.

### Local Profile

`application-local.yml` sets:

**`import-path`**

- Default: `/dev/config`
- Purpose: Base path for optional sandbox credential/config imports.

**`management.endpoints.web.exposure.include`**

- Default: `*`
- Purpose: Exposes all actuator endpoints locally.

**`management.tracing.enabled`**

- Default: `false`
- Purpose: Disables tracing locally.

**`management.defaults.metrics.export.enabled`**

- Default: `false`
- Purpose: Disables default metrics export locally.

**`logging.level.root`**

- Default: `INFO`
- Purpose: Default local log level.

**`logging.level.com.bricklink.api`**

- Default: `DEBUG`
- Purpose: Enables BrickLink REST debug logs locally.

**`lego.minio.url`**

- Default: `https://api.minio.dev.internal.legohunter.io`
- Purpose: Local MinIO URL.


### Sandbox Profile

`application-sandbox.yml` imports these external files from `${import-path}`:

**`database-configuration.yml`**

- Required for: DB access
- Typical contents: `lego.data`, `lego.databases`, datasource-related settings.

**`kafka-configuration.yml`**

- Required for: Kafka auth
- Typical contents: `lego.kafka.consumer.username/password`, `lego.kafka.producer.username/password`.

**`minio-configuration.yml`**

- Required for: S3/MinIO access
- Typical contents: `lego.minio.access-key`, `lego.minio.secret-key`.

**`flickr-configuration.yml`**

- Required for: Flickr image hosting
- Typical contents: `flickr.user-id`, `flickr.secrets.*`, optional debug flags.

**`bitly-configuration.yml`**

- Required for: Short URLs for image-hosting apply
- Typical contents: `bitly.base-url`, `bitly.access-token`, `bitly.group-guid`.

**`bricklink-client-api-keys.yml`**

- Required for: BrickLink REST order sync
- Typical contents: `bricklink.rest.consumer.*`, `bricklink.rest.token.*`.


All imports are currently plain `file:` imports, so missing files fail startup unless the runtime supplies them.

### Kubernetes Profile

`application-kubernetes.yml` sets:

**`import-path`**

- Value: `/etc/.credentials`
- Purpose: Location where external secrets are projected.

**`spring.main.banner-mode`**

- Value: `off`
- Purpose: Quiet startup banner.

**`management.endpoints.web.exposure.include`**

- Value: `health,info,prometheus,metrics`
- Purpose: Restricts actuator exposure.

**`management.endpoint.health.probes.enabled`**

- Value: `true`
- Purpose: Enables Kubernetes liveness/readiness probes.

**`management.endpoint.health.group.liveness.include`**

- Value: `livenessState`
- Purpose: Liveness group contents.

**`management.endpoint.health.group.readiness.include`**

- Value: `readinessState,db,imageHostingReadiness`
- Purpose: Readiness group contents.

**`management.tracing.sampling.probability`**

- Value: `1.0`
- Purpose: Samples all traces when tracing is enabled by the runtime.

**`kafka.properties.security.protocol`**

- Value: `SASL_PLAINTEXT`
- Purpose: Cluster-internal Kafka security protocol override.

**`lego.minio.url`**

- Value: `http://minio.minio-dev.svc.cluster.local`
- Purpose: Cluster-internal MinIO URL.


## REST Controllers

All current business controllers are internal image-hosting controllers under `/internal/image-hosting`. There are no request bodies; inputs are path variables and query parameters.

Detailed response examples and workflow guidance for the image-hosting endpoints are also maintained in [docs/image-hosting-api.md](docs/image-hosting-api.md).

### Provider Selection Parameters

Most image-hosting endpoints accept the same provider selection parameters.

**`provider`**

- Required: No
- Default: `lego.image-hosting.default-provider`
- Valid values: A configured key under `lego.image-hosting.providers`, currently `flickr`
- Description: Selects provider-specific defaults.

**`externalServiceId`**

- Required: No
- Default: Provider `external-service-id`, otherwise `lego.image-hosting.sync.external-service-id`
- Valid values: Integer matching an `external_service` row
- Description: Overrides the external service id used for DB lookups/writes.


### Remote Read Parameters

Remote snapshot, sync-plan, and repair-plan endpoints can read remote provider state.

**`userId`**

- Required: No
- Default: Provider/client configuration where supported
- Valid values: Flickr user id, for example `144144385@N08`
- Description: Explicit remote account/user selection.

**`albumPageSize`**

- Required: No
- Default: `500`
- Valid values: Positive integer accepted by the provider
- Description: Page size for remote album listing.

**`photoPageSize`**

- Required: No
- Default: `500`
- Valid values: Positive integer accepted by the provider
- Description: Page size for remote album photo listing.


### Controller Summary

`POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync`

- Controller: `ImageHostingSyncController`
- Writes remote provider: yes when `dryRun=false`
- Writes DB: yes when `dryRun=false`
- Purpose: legacy direct image-hosting sync workflow for one item inventory. Prefer sync-plan endpoints for controlled operations.

`GET /internal/image-hosting/item-inventories/{itemInventoryId}/generated-description`

- Controller: `GeneratedDescriptionController`
- Writes remote provider: no
- Writes DB: no
- Purpose: preview the generated DB-backed title/description used by image-hosting sync.

`GET /internal/image-hosting/remote/albums/{albumId}/snapshot`

- Controller: `ImageHostingRemoteSnapshotController`
- Writes remote provider: no
- Writes DB: no
- Purpose: read the current remote album/photos state for inspection.

`GET /internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan`

- Controller: `ImageHostingSyncPlanController`
- Writes remote provider: no
- Writes DB: no
- Purpose: build a dry-run reconciliation plan comparing DB desired state to remote image-hosting state.

`POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan/apply`

- Controller: `ImageHostingSyncPlanController`
- Writes remote provider: yes, depending on plan actions
- Writes DB: yes, depending on plan actions
- Purpose: rebuild and apply the current sync plan.

`GET /internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan`

- Controller: `ImageHostingDbRepairController`
- Writes remote provider: no
- Writes DB: no
- Purpose: build a DB repair plan from current remote image-hosting state.

`POST /internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan/apply`

- Controller: `ImageHostingDbRepairController`
- Writes remote provider: usually no, but safe provider membership operations may occur
- Writes DB: yes
- Purpose: apply DB link repair actions.

### Endpoint Parameters

- `POST /item-inventories/{itemInventoryId}/sync`: `dryRun` defaults to `true`; `retryFailed` defaults to `false`; also accepts `provider` and `externalServiceId`.
- `GET /item-inventories/{itemInventoryId}/generated-description`: accepts `provider` and `externalServiceId`.
- `GET /remote/albums/{albumId}/snapshot`: accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`.
- `GET /item-inventories/{itemInventoryId}/sync-plan`: accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`.
- `POST /item-inventories/{itemInventoryId}/sync-plan/apply`: same as sync-plan plus `allowReviewRequired`, default `false`.
- `GET /item-inventories/{itemInventoryId}/repair-plan`: accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`.
- `POST /item-inventories/{itemInventoryId}/repair-plan/apply`: same as repair-plan plus `allowReviewRequired`, default `false`.

Examples:

```http
GET /internal/image-hosting/item-inventories/335/generated-description
GET /internal/image-hosting/item-inventories/335/sync-plan
POST /internal/image-hosting/item-inventories/335/sync-plan/apply
POST /internal/image-hosting/item-inventories/335/sync-plan/apply?allowReviewRequired=true
GET /internal/image-hosting/remote/albums/72157713151696202/snapshot?provider=flickr
GET /internal/image-hosting/item-inventories/335/repair-plan
POST /internal/image-hosting/item-inventories/335/repair-plan/apply
POST /internal/image-hosting/item-inventories/335/sync?dryRun=false&retryFailed=true
```

Operational notes:

- Plan-first safety: `POST /sync-plan/apply` rebuilds the plan before applying. Re-check if remote state may have changed since the last `GET /sync-plan`.
- Review-required actions: blocked unless `allowReviewRequired=true`. Do not use this switch blindly in automation.
- Scheduled job integration: the scheduled image-hosting job uses the same planner/executor path.
- Repair endpoints: use when Flickr already has the album/photos but DB rows in `external_image`, `external_image_album`, or `external_image_album_image` are missing or stale.

## Scheduled Jobs

Scheduling is enabled globally by `io.legohunter.ingress.scheduling.config.SchedulingConfiguration` using `@EnableScheduling`. ShedLock is configured by `SchedulerConfiguration`, so scheduled jobs should have lock rows available in the database configured by the application.

### `BricklinkOpenOrderProbeJob`

Class: `io.legohunter.ingress.source.bricklink.orders.BricklinkOpenOrderProbeJob`

Condition:

```yaml
lego.bricklink.orders.sync.scheduled.enabled: true
```

Schedule:

**`lego.bricklink.orders.sync.scheduled.fixed-delay-ms`**

- Default: `300000` in code, `60000` in base YAML
- Valid values: Long milliseconds, >= 0
- Description: Delay between the end of one run and the start of the next.

**`lego.bricklink.orders.sync.scheduled.initial-delay-ms`**

- Default: `30000` in code, `5000` in base YAML
- Valid values: Long milliseconds, >= 0
- Description: Delay after app startup before first run.

**`lego.bricklink.orders.sync.scheduled.lock-at-most-for`**

- Default: `10m`
- Valid values: ShedLock duration, for example `30s`, `10m`, `1h`
- Description: Maximum distributed lock duration.

**`lego.bricklink.orders.sync.scheduled.lock-at-least-for`**

- Default: `0s`
- Valid values: ShedLock duration
- Description: Minimum distributed lock duration.


Operational modes:

**`false`**

- Behavior: Probe mode. Authenticates to BrickLink, lists configured open order statuses, fetches order details and items, logs coverage/counters, writes no marketplace sync rows.

**`true`**

- Behavior: Write-side sync mode. Creates a sync-run row, upserts marketplace orders, syncs marketplace order items, stores raw payload audit rows, and deletes stale item rows no longer present in the latest response.


Data written when `apply=true`:

**`marketplace_order_sync_run`**

- Write behavior: Inserted at run start as `STARTED`; updated at completion as `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`, or `NO_WORK`.

**`marketplace_order`**

- Write behavior: Upserted by marketplace/order natural key. Tracks status, buyer/shipping/payment/cost fields, counts, weights, flags, payload hash, and last seen time.

**`marketplace_order_item`**

- Write behavior: Existing rows are updated by stable generated external order item id; new rows are inserted; stale rows for the order are deleted.

**`marketplace_order_payload`**

- Write behavior: Inserts raw JSON audit payloads for order detail and order items responses with SHA-256 hashes.


BrickLink API calls per run:

**List orders**

- Description: Calls order list endpoint once per configured status.

**Include cancelled**

- Description: If `include-unfiled-cancelled=true`, also lists `CANCELLED` with `filed=false` unless already included in `statuses`.

**Fetch detail**

- Description: Calls get-order detail for each unique discovered order id.

**Fetch items**

- Description: Calls get-order-items for each fetched order.


Important logs:

**`bricklink.order_sync.probe.started`**

- Meaning: Job started with configured direction/statuses/apply mode.

**`bricklink.order_sync.probe.order_query_failed`**

- Meaning: A status query failed; job continues with other statuses.

**`bricklink.order_sync.probe.order_fetched`**

- Meaning: One order detail and item list were fetched.

**`bricklink.order_sync.probe.coverage`**

- Meaning: Field coverage for one fetched order.

**`bricklink.order_sync.probe.item_coverage`**

- Meaning: Field coverage across fetched order items.

**`bricklink.order_sync.probe.completed`**

- Meaning: Job completed with counts and elapsed time.

**`bricklink.order_sync.probe.failed`**

- Meaning: Job-level failure.


### `ImageHostingScheduledSyncJob`

Class: `io.legohunter.egress.imagehosting.ImageHostingScheduledSyncJob`

Condition:

```yaml
lego.image-hosting.sync.scheduled.enabled: true
```

Schedule and selection settings:

**`lego.image-hosting.sync.scheduled.fixed-delay-ms`**

- Default: `300000`
- Valid values: Long milliseconds, >= 0
- Description: Delay between job runs.

**`lego.image-hosting.sync.scheduled.initial-delay-ms`**

- Default: `30000`
- Valid values: Long milliseconds, >= 0
- Description: Delay after startup before first run.

**`lego.image-hosting.sync.scheduled.lock-at-most-for`**

- Default: `10m`
- Valid values: ShedLock duration
- Description: Maximum distributed lock duration.

**`lego.image-hosting.sync.scheduled.lock-at-least-for`**

- Default: `0s`
- Valid values: ShedLock duration
- Description: Minimum distributed lock duration.

**`lego.image-hosting.sync.scheduled.batch-size`**

- Default: `25`
- Valid values: Integer; effective value is at least `1`
- Description: Maximum candidate item inventories selected per run.

**`lego.image-hosting.sync.scheduled.concurrency`**

- Default: `2` in code, `5` in base YAML, `2` in Kubernetes
- Valid values: Integer; effective value is at least `1`
- Description: Thread pool size for inventory sync work.

**`lego.image-hosting.sync.scheduled.retry-failed`**

- Default: `true`
- Valid values: `true`, `false`
- Description: Whether failed sync rows are eligible for selection.

**`lego.image-hosting.sync.scheduled.apply`**

- Default: `false`
- Valid values: `true`, `false`
- Description: If `false`, build/log plans only. If `true`, execute applicable plan actions.


Candidate reasons logged by the job:

**`missingAlbumLink`**

- Meaning: Inventory item needs an external album link.

**`missingPhotoLink`**

- Meaning: One or more photos need external image links.

**`failedSync`**

- Meaning: Prior sync rows failed and retry is enabled.

**`pendingSync`**

- Meaning: Rows are pending sync.

**`metadataChanged`**

- Meaning: Local metadata differs from metadata last synced to the provider.


Outcomes:

**`NO_WORK`**

- Meaning: No candidate item inventories selected.

**`SUCCESS`**

- Meaning: Candidates were processed and none failed.

**`PARTIAL_FAILURE`**

- Meaning: At least one candidate succeeded and at least one failed.

**`FAILED`**

- Meaning: All selected candidates failed.


Important logs:

**`image_hosting.sync_job.started`**

- Meaning: Job started with provider, batch size, concurrency, retry, and apply mode.

**`image_hosting.sync_job.candidates_selected`**

- Meaning: Candidate count breakdown by reason.

**`image_hosting.sync_job.no_work`**

- Meaning: No candidates selected.

**`image_hosting.sync_job.inventory_no_actions`**

- Meaning: Plan had no actions for one inventory item.

**`image_hosting.sync_job.inventory_planned`**

- Meaning: Apply is false and actions were planned but not executed.

**`image_hosting.sync_job.inventory_completed`**

- Meaning: One inventory item completed in apply mode.

**`image_hosting.sync_job.inventory_failed`**

- Meaning: One inventory item failed; job continues.

**`image_hosting.sync_job.completed`**

- Meaning: Run-level result.


### `TestCronJob`

Class: `io.legohunter.ingress.scheduling.job.TestCronJob`

This job is intentionally disabled in code:

```java
@Scheduled(cron = "-")
```

It has a ShedLock declaration but will not execute unless the cron expression is changed in code.

## Kafka And S3 Event Pipelines

These flows are event-driven rather than `@Scheduled`, but operationally they behave like asynchronous batch pipelines.

### Dynamic Kafka Bean Registration

`KafkaDynamicBeanRegistrar` reads `kafka.topic-configuration.*` and creates beans using these naming conventions:

**`upload-photo`**

- Consumer factory bean: `uploadPhotoConsumerFactory`
- Kafka template bean: `uploadPhotoKafkaTemplate` if producer configured
- Container factory bean: `uploadPhotoContainerFactory`

**`bricklink-catalog-entry`**

- Consumer factory bean: `bricklinkCatalogEntryConsumerFactory`
- Kafka template bean: `bricklinkCatalogEntryKafkaTemplate` if producer configured
- Container factory bean: `bricklinkCatalogEntryContainerFactory`


Rules:

**`kafka.topic-configuration.<id>.consumer` present**

- Behavior: Registers a consumer factory and listener container factory.

**`kafka.topic-configuration.<id>.producer` present**

- Behavior: Registers a producer factory and Kafka template.

**Hyphenated Kafka property keys**

- Behavior: Converted to dotted Kafka client keys, for example `max-poll-records` becomes `max.poll.records`.

**Sensitive properties**

- Behavior: `sasl.jaas.config`, SSL passwords, and key passwords are masked in registrar logs.


### MinIO S3 Event Router

Listener: `MinioS3EventListener`

Input topic:

```yaml
kafka.topic-configuration.minio-s3.topics
```

Group id:

```yaml
kafka.topic-configuration.minio-s3.consumer.group-id
```

Container factory: `minioS3ContainerFactory`

Routing rules:

**`s3:ObjectCreated:*`, key starts `photos/` and not `photos/rejected/` or `photos/duplicate/`**

- Routed to topic setting: `kafka.topic-configuration.upload-photo.topic`
- Notes: Photo processing pipeline.

**`s3:ObjectCreated:*`, key starts `bricklink/catalog/`**

- Routed to topic setting: `kafka.topic-configuration.upload-bricklink-catalog.topic`
- Notes: BrickLink catalog XML pipeline.

**`s3:ObjectCreated:*`, key starts `bricklink/category/`**

- Routed to topic setting: `kafka.topic-configuration.upload-bricklink-category.topic`
- Notes: BrickLink category XML pipeline.

**`s3:ObjectCreated:*`, key starts `rebrickable/catalog/`**

- Routed to topic setting: `kafka.topic-configuration.upload-rebrickable-catalog.topic`
- Notes: Rebrickable catalog CSV gzip pipeline.

**`s3:ObjectCreated:*`, key starts `rebrickable/category/`**

- Routed to topic setting: `kafka.topic-configuration.upload-rebrickable-theme.topic`
- Notes: Rebrickable theme CSV gzip pipeline.

**`s3:ObjectRemoved:*`, bucket `lego-photos-sandbox`, key shape `<dir>/<dir>/<32-hex-md5>.jpg`**

- Routed to topic setting: `kafka.topic-configuration.delete-photo.topic`
- Notes: Final photo deletion pipeline.


Unsupported object events are logged as warnings and are not routed.

### Kafka Pipeline Summary

**Photo upload**

- Listener: `PhotoUploadS3EventListener`
- Input: `upload-photo.topic`
- Output/write: Calls `PhotoProcessingService.process(PhotoUploadEvent)`.

**Photo delete**

- Listener: `PhotoDeletedS3EventListener`
- Input: `delete-photo.topic`
- Output/write: Calls `PhotoDeletionService.process(ObjectDeletedEvent)`.

**BrickLink catalog file split**

- Listener: `BricklinkCatalogS3EventListener`
- Input: `upload-bricklink-catalog.topic`
- Output/write: Reads XML from MinIO and publishes one `CatalogEntry` per `ITEM` to `bricklink-catalog-entry.topic`.

**BrickLink catalog DB upsert**

- Listener: `BricklinkCatalogEntryConsumer`
- Input: `bricklink-catalog-entry.topic`
- Output/write: Upserts `external_catalog_item` and category link when category exists.

**BrickLink category file split**

- Listener: `BricklinkCategoryS3EventListener`
- Input: `upload-bricklink-category.topic`
- Output/write: Reads XML from MinIO and publishes one `CategoryEntry` per `ITEM` to `bricklink-category-entry.topic`.

**BrickLink category DB upsert**

- Listener: `BricklinkCategoryEntryConsumer`
- Input: `bricklink-category-entry.topic`
- Output/write: Upserts `external_category`.

**Rebrickable catalog file split**

- Listener: `RebrickableCatalogS3EventListener`
- Input: `upload-rebrickable-catalog.topic`
- Output/write: Reads gzipped CSV and publishes one `RebrickableCatalogEntry` per row to `rebrickable-catalog-entry.topic`.

**Rebrickable catalog DB upsert**

- Listener: `RebrickableCatalogEntryConsumer`
- Input: `rebrickable-catalog-entry.topic`
- Output/write: Upserts `external_catalog_item` and category link when category exists.

**Rebrickable theme file split**

- Listener: `RebrickableThemeS3EventListener`
- Input: `upload-rebrickable-theme.topic`
- Output/write: Reads gzipped CSV and publishes one `RebrickableThemeEntry` per row to `rebrickable-theme-entry.topic`.

**Rebrickable theme DB upsert**

- Listener: `RebrickableThemeEntryConsumer`
- Input: `rebrickable-theme-entry.topic`
- Output/write: Upserts `external_category` and resolves parent category when present.


Notes:

**Photo upload concurrency**

- Detail: Configurable through `kafka.topic-configuration.upload-photo.consumer.concurrency`, default `1`.

**Photo delete concurrency**

- Detail: Hard-coded to `6` in the listener annotation; not currently YAML-configurable.

**BrickLink external service id**

- Detail: Catalog/category consumers currently hard-code `2`.

**Rebrickable external service id**

- Detail: Catalog/theme consumers currently hard-code `9`.

**Rebrickable catalog category lookup**

- Detail: The current catalog category lookup uses external service id `2`; review before relying on Rebrickable category links.


Expected Rebrickable CSV headers:

**Catalog**

- Headers: `SET_NUM`, `NAME`, `YEAR`, `THEME_ID`, `NUM_PARTS`, `IMG_URL`

**Theme/category**

- Headers: `ID`, `NAME`, `PARENT_ID`


## Configuration Reference

### `lego.bricklink.orders.sync.*`

Backed by `BricklinkOrderSyncProperties`.

`lego.bricklink.orders.sync.marketplace-code`

- Default: `BRICKLINK`
- Valid values: non-blank string; normalized to uppercase
- Description: marketplace code written to marketplace sync tables.

`lego.bricklink.orders.sync.metrics-tag`

- Default: `bricklink`
- Valid values: non-blank string
- Description: low-cardinality provider tag for metrics.

`lego.bricklink.orders.sync.direction`

- Default: `in`
- Valid values: BrickLink API direction, normally `in` for seller/inbound orders or `out` for buyer/outbound orders
- Description: direction sent to BrickLink order list calls and written to marketplace orders.

`lego.bricklink.orders.sync.statuses`

- Default: `PENDING`, `UPDATED`, `READY`, `PROCESSING`, `PAID`, `PACKED`
- Valid values: BrickLink API order status strings. Code trims, uppercases, and de-duplicates values.
- Description: statuses queried every run.

`lego.bricklink.orders.sync.include-unfiled-cancelled`

- Default: `true`
- Valid values: `true`, `false`
- Description: also query unfiled cancelled orders using `filed=false&status=CANCELLED`.

`lego.bricklink.orders.sync.scheduled.enabled`

- Default: `false` in code; `true` in base YAML
- Valid values: `true`, `false`
- Description: creates scheduled job/service beans when true.

`lego.bricklink.orders.sync.scheduled.apply`

- Default: `false`
- Valid values: `true`, `false`
- Description: enables database writes when true. Keep false for probe-only validation.

`lego.bricklink.orders.sync.scheduled.fixed-delay-ms`

- Default: `300000` in code; `60000` in base YAML
- Valid values: long milliseconds, `>= 0`
- Description: delay between runs.

`lego.bricklink.orders.sync.scheduled.initial-delay-ms`

- Default: `30000` in code; `5000` in base YAML
- Valid values: long milliseconds, `>= 0`
- Description: first-run startup delay.

`lego.bricklink.orders.sync.scheduled.lock-at-most-for`

- Default: `10m`
- Valid values: ShedLock duration string
- Description: maximum distributed lock time.

`lego.bricklink.orders.sync.scheduled.lock-at-least-for`

- Default: `0s`
- Valid values: ShedLock duration string
- Description: minimum distributed lock time.

Recommended safe defaults:

```yaml
lego:
  bricklink:
    orders:
      sync:
        marketplace-code: BRICKLINK
        metrics-tag: bricklink
        direction: in
        statuses: [PENDING, UPDATED, READY, PROCESSING, PAID, PACKED]
        include-unfiled-cancelled: true
        scheduled:
          enabled: true
          apply: false
```

### `bricklink.rest.*`

Backed by `bricklink-rest` dependency `BricklinkRestProperties`.

`bricklink.rest.uri`

- Default: none in properties; `https://api.bricklink.com/api/store/v1` in base YAML
- Valid values: absolute URI
- Description: BrickLink REST base URI. Use `/api/store/v1`, not `/v2`.

`bricklink.rest.consumer.key`

- Default: none
- Valid values: BrickLink API consumer key
- Description: OAuth consumer key. Secret, externalized.

`bricklink.rest.consumer.secret`

- Default: none
- Valid values: BrickLink API consumer secret
- Description: OAuth consumer secret. Secret, externalized.

`bricklink.rest.token.value`

- Default: none
- Valid values: BrickLink API token value
- Description: OAuth access token. Secret, externalized.

`bricklink.rest.token.secret`

- Default: none
- Valid values: BrickLink API token secret
- Description: OAuth token secret. Secret, externalized.

`bricklink.rest.http-logging.enabled`

- Default: `false` in dependency; `true` in base YAML
- Valid values: `true`, `false`
- Description: adds request/response logging interceptor when true.

`bricklink.rest.http-logging.include-headers`

- Default: `false` in dependency; `true` in base YAML
- Valid values: `true`, `false`
- Description: includes headers in debug logs. Sensitive headers/cookies are redacted by the interceptor.

`bricklink.rest.http-logging.include-body`

- Default: `true`
- Valid values: `true`, `false`
- Description: includes request/response body in debug logs.

`bricklink.rest.http-logging.max-body-length`

- Default: `-1`
- Valid values: `-1` for unlimited, or non-negative max characters
- Description: truncates logged bodies when non-negative.

Operational notes:

- HTTP `302` to `/v2/error_404.page`: usually caused by a wrong base URI such as `https://api.bricklink.com/v2`. Set `bricklink.rest.uri` to `https://api.bricklink.com/api/store/v1`.
- `401`/OAuth errors: usually caused by missing or invalid consumer/token secrets. Check `${import-path}/bricklink-client-api-keys.yml`.
- Very large logs: usually caused by `include-body=true` and `max-body-length=-1`. Set a finite max body length or disable body logging.

### `lego.image-hosting.*`

Backed by `ImageHostingSyncProperties`.

**`lego.image-hosting.default-provider`**

- Default: `flickr`
- Valid values: Key under `lego.image-hosting.providers`
- Description: Provider used when request/job omits `provider`.

**`lego.image-hosting.providers.<provider>.enabled`**

- Default: `false` in code, `true` for Flickr in base YAML
- Valid values: `true`, `false`
- Description: Enables provider configuration. Readiness expects Flickr enabled when sync runtime is required.

**`lego.image-hosting.providers.<provider>.external-service-id`**

- Default: None
- Valid values: Integer external service id
- Description: DB external service id for provider rows. Flickr is currently `10`.

**`lego.image-hosting.providers.<provider>.display-name`**

- Default: Provider key
- Valid values: String
- Description: Human-readable provider name.

**`lego.image-hosting.providers.<provider>.metrics-tag`**

- Default: Provider key
- Valid values: Low-cardinality string
- Description: Metrics provider tag.

**`lego.image-hosting.sync.external-service-id`**

- Default: `10`
- Valid values: Integer
- Description: Fallback external service id when provider config does not supply one.

**`lego.image-hosting.sync.temp-directory`**

- Default: `${java.io.tmpdir}/lego-data-ingress-image-hosting`
- Valid values: Filesystem path
- Description: Temporary directory used when adapting S3 objects to file-path based provider APIs.


#### Image-Hosting Retry

**`lego.image-hosting.sync.retry.enabled`**

- Default: `true`
- Valid values: `true`, `false`
- Description: Enables provider retry wrapper. If false, effective max attempts is `1`.

**`lego.image-hosting.sync.retry.max-attempts`**

- Default: `3`
- Valid values: Integer; effective value at least `1`
- Description: Maximum attempts.

**`lego.image-hosting.sync.retry.initial-backoff-ms`**

- Default: `500`
- Valid values: Long milliseconds; effective value at least `0`
- Description: Initial retry delay.

**`lego.image-hosting.sync.retry.backoff-multiplier`**

- Default: `2.0`
- Valid values: Double; effective value at least `1.0`
- Description: Exponential backoff multiplier.

**`lego.image-hosting.sync.retry.max-backoff-ms`**

- Default: `5000`
- Valid values: Long milliseconds; effective value at least initial backoff
- Description: Maximum retry delay.


#### Image-Hosting Scheduled Sync

**`lego.image-hosting.sync.scheduled.enabled`**

- Default: `false`
- Valid values: `true`, `false`
- Description: Enables scheduled image-hosting sync job.

**`lego.image-hosting.sync.scheduled.batch-size`**

- Default: `25`
- Valid values: Integer; effective value at least `1`
- Description: Candidate item inventory limit per run.

**`lego.image-hosting.sync.scheduled.concurrency`**

- Default: `2` in code, `5` in base YAML, `2` in Kubernetes
- Valid values: Integer; effective value at least `1`
- Description: Worker thread count.

**`lego.image-hosting.sync.scheduled.retry-failed`**

- Default: `true`
- Valid values: `true`, `false`
- Description: Includes failed sync rows in candidate query.

**`lego.image-hosting.sync.scheduled.apply`**

- Default: `false`
- Valid values: `true`, `false`
- Description: If false, plans only. If true, executes plan actions.

**`lego.image-hosting.sync.scheduled.fixed-delay-ms`**

- Default: `300000`
- Valid values: Long milliseconds, >= 0
- Description: Delay between runs.

**`lego.image-hosting.sync.scheduled.initial-delay-ms`**

- Default: `30000`
- Valid values: Long milliseconds, >= 0
- Description: First-run startup delay.

**`lego.image-hosting.sync.scheduled.lock-at-most-for`**

- Default: `10m`
- Valid values: ShedLock duration string
- Description: Maximum distributed lock time.

**`lego.image-hosting.sync.scheduled.lock-at-least-for`**

- Default: `0s`
- Valid values: ShedLock duration string
- Description: Minimum distributed lock time.


#### Image-Hosting Publishing

**`lego.image-hosting.publishing.photo.title-template`**

- Default: `{captionOrFilename}`
- Valid values: Template string
- Description: Photo title template used for provider publishing.

**`lego.image-hosting.publishing.photo.description-template`**

- Default: `{captionOrTitle}`
- Valid values: Template string
- Description: Photo description template.

**`lego.image-hosting.publishing.photo.tags`**

- Default: `[]`
- Valid values: List of strings
- Description: Provider tags added to photos.

**`lego.image-hosting.publishing.photo.public-flag`**

- Default: `true`
- Valid values: `true`, `false`
- Description: Flickr public visibility flag.

**`lego.image-hosting.publishing.photo.friend-flag`**

- Default: `false`
- Valid values: `true`, `false`
- Description: Flickr friend visibility flag.

**`lego.image-hosting.publishing.photo.family-flag`**

- Default: `false`
- Valid values: `true`, `false`
- Description: Flickr family visibility flag.

**`lego.image-hosting.publishing.photo.hidden`**

- Default: `false`
- Valid values: `true`, `false`
- Description: Provider hidden flag.

**`lego.image-hosting.publishing.photo.safety-level`**

- Default: `safe`
- Valid values: Provider-supported safety value, currently `safe` by default
- Description: Flickr safety level.


#### Image-Hosting Readiness

**`lego.image-hosting.readiness.enabled`**

- Default: `true`
- Valid values: `true`, `false`
- Description: Enables `imageHostingReadiness` health indicator and startup readiness logs.

**`lego.image-hosting.readiness.require-scheduled-sync-enabled`**

- Default: `false` in base YAML, `true` in Kubernetes
- Valid values: `true`, `false`
- Description: If true, readiness is DOWN when scheduled sync is disabled.

**`lego.image-hosting.readiness.require-bitly-when-apply-enabled`**

- Default: `true`
- Valid values: `true`, `false`
- Description: If true, Bitly config is required when scheduled sync and apply are both enabled.


### `flickr.*`

Backed by `lego-imaging` `FlickrProperties`.

**`flickr.application-name`**

- Default: None
- Valid values: String
- Description: Application name passed to Flickr integration where used.

**`flickr.debug-request`**

- Default: `false` when omitted
- Valid values: `true`, `false`
- Description: Enables Flickr request debug mode in `lego-imaging`.

**`flickr.debug-stream`**

- Default: `false` when omitted
- Valid values: `true`, `false`
- Description: Enables Flickr stream debug mode in `lego-imaging`.

**`flickr.user-id`**

- Default: None
- Valid values: Flickr user id
- Description: Required by image-hosting readiness when sync runtime is required.

**`flickr.secrets.key`**

- Default: None
- Valid values: Secret string
- Description: Flickr API key.

**`flickr.secrets.secret`**

- Default: None
- Valid values: Secret string
- Description: Flickr API secret.

**`flickr.secrets.token`**

- Default: None
- Valid values: Secret string
- Description: Flickr OAuth token.

**`flickr.secrets.token-secret`**

- Default: None
- Valid values: Secret string
- Description: Flickr OAuth token secret.


### `bitly.*`

Backed by `lego-imaging` `BitlyProperties`.

**`bitly.base-url`**

- Default: `https://api-ssl.bitly.com/v4`
- Valid values: Absolute URI
- Description: Bitly API base URL.

**`bitly.access-token`**

- Default: None
- Valid values: Secret string
- Description: Bitly access token. Required for short URL operations.

**`bitly.group-guid`**

- Default: None
- Valid values: Bitly group GUID
- Description: Required for short URL operations.

**`bitly.oauth2.client-id`**

- Default: None
- Valid values: Secret string
- Description: OAuth2 client id, currently not used by the readiness check.

**`bitly.oauth2.client-secret`**

- Default: None
- Valid values: Secret string
- Description: OAuth2 client secret, currently not used by the readiness check.


### `lego.minio.*`

Backed by `S3ClientProperties`.

**`lego.minio.url`**

- Default: Local/Kubernetes profile sets environment-specific URL
- Valid values: Absolute URI
- Description: MinIO/S3 endpoint.

**`lego.minio.access-key`**

- Default: None
- Valid values: Secret string
- Description: MinIO access key.

**`lego.minio.secret-key`**

- Default: None
- Valid values: Secret string
- Description: MinIO secret key.


`lego.minio.url`, `access-key`, and `secret-key` are required by image-hosting readiness when image-hosting sync runtime is required.

### `kafka.*`

Backed by `KafkaCustomProperties` and Spring Kafka properties.

**`kafka.bootstrap-servers`**

- Default/Example: Redpanda dev brokers
- Valid values: List of host:port strings
- Description: Kafka bootstrap servers.

**`kafka.client-id`**

- Default/Example: None
- Valid values: String
- Description: Optional Kafka client id.

**`kafka.properties.*`**

- Default/Example: Security and SASL settings
- Valid values: Kafka client properties
- Description: Common properties applied to all producers/consumers.

**`kafka.consumer-defaults.*`**

- Default/Example: Sandbox defines deserializers/auth
- Valid values: Spring Kafka consumer properties
- Description: Defaults merged into every topic consumer.

**`kafka.producer-defaults.*`**

- Default/Example: Sandbox defines serializers/retries/auth
- Valid values: Spring Kafka producer properties
- Description: Defaults merged into every topic producer.

**`kafka.topic-configuration.<id>.topic`**

- Default/Example: Topic-specific
- Valid values: Single topic name
- Description: Used by most listeners/templates.

**`kafka.topic-configuration.<id>.topics`**

- Default/Example: Topic-specific
- Valid values: Comma-separated or YAML list of topic names
- Description: Used by listeners that subscribe to multiple topics, such as `minio-s3`.

**`kafka.topic-configuration.<id>.consumer.group-id`**

- Default/Example: Topic-specific
- Valid values: String
- Description: Consumer group id.

**`kafka.topic-configuration.<id>.consumer.concurrency`**

- Default/Example: Topic-specific
- Valid values: Integer
- Description: Used only where the listener annotation references it. Currently `upload-photo`.

**`kafka.topic-configuration.<id>.consumer.properties.*`**

- Default/Example: Topic-specific
- Valid values: Kafka/Spring Kafka properties
- Description: Per-topic consumer overrides. Hyphens are converted to dots.

**`kafka.topic-configuration.<id>.producer.*`**

- Default/Example: Topic-specific
- Valid values: Kafka producer properties
- Description: Per-topic producer overrides. Hyphens are converted to dots.


Sandbox topic ids currently configured:

**`minio-s3`**

- Role: Raw MinIO S3 event input.

**`upload-minio-s3`**

- Role: Producer for routed upload/delete events.

**`upload-photo`**

- Role: Routed photo upload events.

**`delete-photo`**

- Role: Routed photo delete events.

**`upload-bricklink-catalog`**

- Role: Routed BrickLink catalog file events.

**`upload-bricklink-category`**

- Role: Routed BrickLink category file events.

**`upload-rebrickable-catalog`**

- Role: Routed Rebrickable catalog file events.

**`upload-rebrickable-theme`**

- Role: Routed Rebrickable theme/category file events.

**`photo-upload-entry`**

- Role: Configured but no current main-code listener found.

**`bricklink-catalog-entry`**

- Role: Parsed BrickLink catalog entries.

**`bricklink-category-entry`**

- Role: Parsed BrickLink category entries.

**`rebrickable-catalog-entry`**

- Role: Parsed Rebrickable catalog entries.

**`rebrickable-theme-entry`**

- Role: Parsed Rebrickable theme entries.


## Readiness And Actuator

### Actuator URLs

**`/actuator/health`**

- Local profile: Exposed
- Kubernetes profile: Exposed
- Purpose: Overall health.

**`/actuator/health/liveness`**

- Local profile: Exposed with all local endpoints
- Kubernetes profile: Exposed
- Purpose: Kubernetes liveness.

**`/actuator/health/readiness`**

- Local profile: Exposed with all local endpoints
- Kubernetes profile: Exposed
- Purpose: Kubernetes readiness including DB and image-hosting readiness.

**`/actuator/prometheus`**

- Local profile: Exposed with all local endpoints
- Kubernetes profile: Exposed
- Purpose: Prometheus scrape endpoint.

**`/actuator/metrics`**

- Local profile: Exposed with all local endpoints
- Kubernetes profile: Exposed
- Purpose: Metrics endpoint.

**`/swagger-ui/index.html`**

- Local profile: Exposed by application
- Kubernetes profile: Exposed unless restricted externally
- Purpose: OpenAPI UI.

**`/v3/api-docs`**

- Local profile: Exposed by application
- Kubernetes profile: Exposed unless restricted externally
- Purpose: OpenAPI JSON.


### `imageHostingReadiness`

Enabled when:

```yaml
lego.image-hosting.readiness.enabled: true
management.health.imageHostingReadiness.enabled: true
```

Checks:

**`scheduled-sync`**

- Required when: Required if `require-scheduled-sync-enabled=true`; otherwise warning when disabled
- Validates: Scheduled sync enabled, batch size > 0, concurrency > 0.

**`database`**

- Required when: Required if scheduled sync is enabled or required
- Validates: `spring.datasource.database-key-name` and `DataSource` bean.

**`s3`**

- Required when: Required if scheduled sync is enabled or required
- Validates: `lego.minio.url`, `lego.minio.access-key`, `lego.minio.secret-key`.

**`flickr`**

- Required when: Required if scheduled sync is enabled or required
- Validates: Flickr provider config, external service id, user id, and secrets.

**`bitly`**

- Required when: Required if scheduled sync enabled, apply enabled, and `require-bitly-when-apply-enabled=true`
- Validates: Bitly base URL, access token, and group GUID.


Startup logs:

**`image_hosting.readiness.startup`**

- Meaning: Overall readiness result at startup.

**`image_hosting.readiness.check`**

- Meaning: One component check with status and requirement flag.


## Metrics

Prometheus uses Micrometer naming conventions. For example, counter `image_hosting_sync` appears as `image_hosting_sync_total` in Prometheus.

### Image Hosting Metrics

**`image_hosting_sync`**

- Type: Counter
- Tags: `provider`, `outcome`, `dry_run`
- Emitted by: Manual direct sync endpoint.

**`image_hosting_sync_duration`**

- Type: Timer
- Tags: `provider`, `outcome`, `dry_run`
- Emitted by: Manual direct sync endpoint.

**`image_hosting_photo_upload`**

- Type: Counter
- Tags: `provider`, `result`
- Emitted by: Manual direct sync photo operations.

**`image_hosting_album_operation`**

- Type: Counter
- Tags: `provider`, `operation`, `result`
- Emitted by: Album create/membership/metadata operations.

**`image_hosting_album_creation`**

- Type: Counter
- Tags: `provider`, `result`
- Emitted by: Sync-plan album creation.

**`image_hosting_album_adoption`**

- Type: Counter
- Tags: `provider`, `result`
- Emitted by: Album adoption/repair planning.

**`image_hosting_short_url`**

- Type: Counter
- Tags: `provider`, `operation`, `result`
- Emitted by: Bitly short URL operations.

**`image_hosting_scheduled_sync`**

- Type: Counter
- Tags: `provider`, `outcome`
- Emitted by: Scheduled image-hosting sync runs.

**`image_hosting_scheduled_sync_duration`**

- Type: Timer
- Tags: `provider`, `outcome`
- Emitted by: Scheduled image-hosting sync runs.

**`image_hosting_scheduled_sync_inventory`**

- Type: Counter
- Tags: `provider`, `result`
- Emitted by: Per-inventory scheduled sync results.


### BrickLink Order Sync Metrics

**`bricklink_order_sync`**

- Type: Counter
- Tags: `provider`, `outcome`
- Description: One count per BrickLink order sync run.

**`bricklink_order_sync_duration`**

- Type: Timer
- Tags: `provider`, `outcome`
- Description: Run duration.

**`bricklink_order_sync_order`**

- Type: Counter
- Tags: `provider`, `result`
- Description: Order counts by `discovered`, `fetched`, `failed`, `written`.


### Photo Processing Metrics

**`photo.processed`**

- Type: Counter
- Tags: `mode`
- Description: Successful photo processing count. `mode` is usually `event` or `batch`.

**`photo.failed`**

- Type: Counter
- Tags: `mode`
- Description: Failed photo processing count.

**`photo.duplicate`**

- Type: Counter
- Tags: `mode`
- Description: Duplicate photo count.

**`photo.skipped`**

- Type: Counter
- Tags: `mode`, `reason`
- Description: Skipped photo count.

**`photo.processing.time`**

- Type: Timer
- Tags: `mode`
- Description: Photo processing duration.


## Structured Logs

### BrickLink REST HTTP Logs

When `bricklink.rest.http-logging.enabled=true` and logger `com.bricklink.api` is DEBUG, the REST client logs:

**`bricklink.rest.request`**

- Fields: HTTP method, URI, headers when enabled, body when enabled.

**`bricklink.rest.response`**

- Fields: HTTP method, URI, status code/text, headers when enabled, body when enabled.


Authorization and cookies are redacted by the logging interceptor.

### BrickLink Order Sync Logs

Primary events:

**`bricklink.order_sync.probe.started`**

- Purpose: Run start with direction/statuses/apply.

**`bricklink.order_sync.probe.no_work`**

- Purpose: No orders discovered.

**`bricklink.order_sync.probe.order_fetched`**

- Purpose: One order fetched with important summary fields.

**`bricklink.order_sync.probe.coverage`**

- Purpose: Order-level field coverage.

**`bricklink.order_sync.probe.item_coverage`**

- Purpose: Item-level field coverage counts.

**`bricklink.order_sync.probe.completed`**

- Purpose: Final outcome and counters.

**`bricklink.order_sync.probe.failed`**

- Purpose: Run-level failure.


### Image Hosting Logs

Primary events:

**`image_hosting.sync.started`**

- Purpose: Manual direct sync started.

**`image_hosting.sync.completed`**

- Purpose: Manual direct sync completed.

**`image_hosting.sync.failed`**

- Purpose: Manual direct sync failed.

**`image_hosting.photo_upload.started`**

- Purpose: Photo upload started.

**`image_hosting.photo_upload.completed`**

- Purpose: Photo upload completed.

**`image_hosting.photo_upload.skipped`**

- Purpose: Photo upload skipped.

**`image_hosting.photo_upload.failed`**

- Purpose: Photo upload failed.

**`image_hosting.photo_metadata_update.started`**

- Purpose: Photo metadata update started.

**`image_hosting.photo_metadata_update.completed`**

- Purpose: Photo metadata update completed.

**`image_hosting.photo_metadata_update.failed`**

- Purpose: Photo metadata update failed.

**`image_hosting.album.create.started`**

- Purpose: Album create started.

**`image_hosting.album.create.completed`**

- Purpose: Album create completed.

**`image_hosting.album.create.failed`**

- Purpose: Album create failed.

**`image_hosting.album_membership.update.started`**

- Purpose: Album membership update started.

**`image_hosting.album_membership.update.completed`**

- Purpose: Album membership update completed.

**`image_hosting.album_membership.update.failed`**

- Purpose: Album membership update failed.

**`image_hosting.sync_job.*`**

- Purpose: Scheduled job lifecycle and per-inventory result events.

**`image_hosting.readiness.*`**

- Purpose: Startup readiness evaluation.


### Photo Logs

Primary events:

**`photo.kafka.consume.start`**

- Purpose: Photo upload event consumption started.

**`photo.kafka.consume.success`**

- Purpose: Photo upload event processed successfully.

**`photo.kafka.consume.complete`**

- Purpose: Photo upload event consumption completed with duration.

**`photo.process.start`**

- Purpose: Photo processing started.

**`photo.process.success`**

- Purpose: Photo processing succeeded.

**`photo.process.source_missing`**

- Purpose: Source object missing for stale S3 event.

**`photo.process.rejected`**

- Purpose: Photo rejected by validation/business rules.

**`photo.process.failed`**

- Purpose: Photo processing failed.

**`photo.process.complete`**

- Purpose: Photo processing completed with duration.

**`photo.delete.kafka.consume.start`**

- Purpose: Photo delete event consumption started.

**`photo.delete.kafka.consume.success`**

- Purpose: Photo delete event processed successfully.

**`photo.delete.kafka.consume.complete`**

- Purpose: Photo delete event consumption completed with duration.

**`photo.delete.process.start`**

- Purpose: Delete service started processing an object delete.


## Rollout Procedures

### Safe BrickLink Order Sync Rollout

1. Confirm BrickLink API credentials are present in `${import-path}/bricklink-client-api-keys.yml`.
2. Set `bricklink.rest.uri=https://api.bricklink.com/api/store/v1`.
3. Keep `lego.bricklink.orders.sync.scheduled.enabled=true`.
4. Keep `lego.bricklink.orders.sync.scheduled.apply=false` for initial validation.
5. Enable DEBUG logs for `com.bricklink.api` only while validating HTTP behavior.
6. Watch `bricklink.rest.request`, `bricklink.rest.response`, and `bricklink.order_sync.probe.*` logs.
7. Confirm order count and coverage logs look correct.
8. Confirm marketplace sync tables exist in the target database before apply mode.
9. Set `lego.bricklink.orders.sync.scheduled.apply=true` only after DB schema and probe logs are verified.
10. After enabling apply, verify `marketplace_order_sync_run`, `marketplace_order`, `marketplace_order_item`, and `marketplace_order_payload` rows.

Rollback:

```yaml
lego:
  bricklink:
    orders:
      sync:
        scheduled:
          apply: false
```

Set `enabled=false` if the job should stop entirely.

### Safe Image-Hosting Scheduled Sync Rollout

1. Start with `lego.image-hosting.sync.scheduled.enabled=true` and `apply=false`.
2. Confirm `/actuator/health/readiness` is `UP` or only has expected non-required warnings.
3. Watch `image_hosting.sync_job.candidates_selected` for candidate counts.
4. Review `image_hosting.sync_job.inventory_planned` to confirm planned action volume.
5. Confirm Flickr credentials and MinIO credentials are present.
6. Confirm Bitly credentials are present before enabling apply because album short URLs may be created or recovered.
7. Set `lego.image-hosting.sync.scheduled.apply=true` only after dry-run plans look correct.
8. Watch `image_hosting.sync_job.inventory_completed`, `image_hosting.album_*`, and `image_hosting.short_url` logs.
9. Use `/actuator/prometheus` to confirm scheduled sync counters and duration timers.

Rollback:

```yaml
lego:
  image-hosting:
    sync:
      scheduled:
        apply: false
```

Set `enabled=false` if the job should stop entirely. In Kubernetes, also consider `lego.image-hosting.readiness.require-scheduled-sync-enabled=false` if disabling the job intentionally.

### Manual Image-Hosting Validation

Read-only validation:

```http
GET /internal/image-hosting/item-inventories/{itemInventoryId}/generated-description
GET /internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan
GET /internal/image-hosting/remote/albums/{albumId}/snapshot
GET /internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan
```

Apply after review:

```http
POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan/apply
```

Use legacy direct sync only when you specifically want the old path:

```http
POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync?dryRun=false
```

## Operational Checks

### Startup

1. Confirm expected profiles in startup logs.
2. Confirm imported credential files are present under `import-path`.
3. Confirm Kafka dynamic bean registration logs appear and sensitive values are masked.
4. Confirm `image_hosting.readiness.startup` if readiness is enabled.
5. Confirm scheduled jobs are either enabled intentionally or absent because the corresponding `enabled` property is false.

### BrickLink Order Sync SQL Checks

After `apply=true`, useful checks:

```sql
select *
from marketplace_order_sync_run
order by marketplace_order_sync_run_id desc
limit 10;

select marketplace_code, external_order_id, order_status, order_direction, ordered_at, grand_total_amount, last_seen_at
from marketplace_order
where marketplace_code = 'BRICKLINK'
order by last_seen_at desc
limit 25;

select oi.*
from marketplace_order_item oi
join marketplace_order o on o.marketplace_order_id = oi.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
order by oi.marketplace_order_id desc, oi.marketplace_order_item_id;

select marketplace_code, payload_type, http_status_code, payload_hash, fetched_at
from marketplace_order_payload
where marketplace_code = 'BRICKLINK'
order by marketplace_order_payload_id desc
limit 25;
```

### Image-Hosting SQL Checks

```sql
select *
from external_image_album
where item_inventory_id = 100
  and external_service_id = 10;

select *
from external_image
where external_service_id = 10
  and item_inventory_photo_id in (
      select item_inventory_photo_id
      from item_inventory_photo
      where item_inventory_id = 100
  );

select ai.*
from external_image_album_image ai
join external_image_album a
  on ai.external_image_album_id = a.external_image_album_id
where a.item_inventory_id = 100
  and a.external_service_id = 10
order by ai.sort_order;
```

### Common Failure Modes

**Application fails because config import is missing**

- Likely cause: Required external file absent under `import-path`
- Check/fix: Create/project the expected YAML file or adjust active profiles.

**BrickLink responses are `302` to error page**

- Likely cause: Wrong BrickLink base URI
- Check/fix: Use `https://api.bricklink.com/api/store/v1`.

**BrickLink sync discovers orders but writes nothing**

- Likely cause: `lego.bricklink.orders.sync.scheduled.apply=false`
- Check/fix: This is probe mode. Set apply true only after DB tables exist and probe output is reviewed.

**Image-hosting scheduled job logs planned actions but no writes**

- Likely cause: `lego.image-hosting.sync.scheduled.apply=false`
- Check/fix: This is dry-run scheduled mode.

**Kubernetes readiness DOWN for image hosting**

- Likely cause: Missing required DB/S3/Flickr/Bitly config or invalid scheduled settings
- Check/fix: Inspect `/actuator/health/readiness` details and startup readiness logs.

**Kafka listener not consuming**

- Likely cause: Topic id missing consumer config or wrong group/topic
- Check/fix: Check `kafka.topic-configuration.<id>.*` and dynamic bean registration logs.

**Kafka JSON deserialization failure**

- Likely cause: Missing/wrong `spring-json-value-default-type` or trusted packages
- Check/fix: Check per-topic consumer properties and `kafka.consumer-defaults.properties.spring.json.trusted.packages`.

**Photo delete throughput setting ignored in YAML**

- Likely cause: Listener concurrency is hard-coded to `6`
- Check/fix: Code change required to make it configurable.


## Safety Rules

- Keep scheduled `apply=false` during first deployments or after large refactors.
- Do not enable BrickLink HTTP body logging in long-running production-like deployments unless needed for diagnosis.
- Do not add high-cardinality metric tags such as order ids, photo ids, album ids, UUIDs, file names, or exception messages.
- Treat all `/internal/*` endpoints as administrative operations. They should remain behind trusted network/access controls.
- Prefer sync-plan and repair-plan endpoints over the legacy direct sync endpoint for manual image-hosting operations.
- Before enabling write-side marketplace sync, confirm the marketplace sync tables and constraints exist in the target database.
