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

| Area | Mode | Purpose |
| --- | --- | --- |
| Photo ingestion | Kafka/S3 event driven | Process uploaded MinIO photo objects into durable DB photo metadata and object-storage state. |
| Photo delete reconciliation | Kafka/S3 event driven | React to final photo object removals. |
| BrickLink catalog ingestion | Kafka/S3 event driven | Parse BrickLink XML catalog/category files and upsert external catalog/category tables. |
| Rebrickable catalog ingestion | Kafka/S3 event driven | Parse Rebrickable gzipped CSV catalog/theme files and upsert external catalog/category tables. |
| Image-hosting sync | REST and scheduled job | Reconcile item inventory photos and albums to Flickr through `lego-imaging`. |
| Image-hosting repair | REST | Repair DB links from current remote image-hosting state. |
| BrickLink order sync | Scheduled job | Poll BrickLink open orders and, when apply mode is enabled, sync marketplace order staging tables. |
| Fulfillment sync | Scheduled job | Convert staged BrickLink marketplace orders into ShipStation orders and reconcile shipped ShipStation orders back to BrickLink. |

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

| Setting | Default | Purpose |
| --- | --- | --- |
| `import-path` | `/dev/config` | Base path for optional sandbox credential/config imports. |
| `management.endpoints.web.exposure.include` | `*` | Exposes all actuator endpoints locally. |
| `management.tracing.enabled` | `false` | Disables tracing locally. |
| `management.defaults.metrics.export.enabled` | `false` | Disables default metrics export locally. |
| `logging.level.root` | `INFO` | Default local log level. |
| `logging.level.com.bricklink.api` | `DEBUG` | Enables BrickLink REST debug logs locally. |
| `lego.minio.url` | `https://api.minio.dev.internal.legohunter.io` | Local MinIO URL. |

### Sandbox Profile

`application-sandbox.yml` imports these external files from `${import-path}`:

| File | Required for | Typical contents |
| --- | --- | --- |
| `database-configuration.yml` | DB access | `lego.data`, `lego.databases`, datasource-related settings. |
| `kafka-configuration.yml` | Kafka auth | `lego.kafka.consumer.username/password`, `lego.kafka.producer.username/password`. |
| `minio-configuration.yml` | S3/MinIO access | `lego.minio.access-key`, `lego.minio.secret-key`. |
| `flickr-configuration.yml` | Flickr image hosting | `flickr.user-id`, `flickr.secrets.*`, optional debug flags. |
| `bitly-configuration.yml` | Short URLs for image-hosting apply | `bitly.base-url`, `bitly.access-token`, `bitly.group-guid`. |
| `bricklink-client-api-keys.yml` | BrickLink REST order sync | `bricklink.rest.consumer.*`, `bricklink.rest.token.*`. |
| ShipStation credentials | Fulfillment sync apply mode | `shipstation.rest.api-key`, `shipstation.rest.api-secret`; currently supply by environment, command line, or another imported config source. |

All imports are currently plain `file:` imports, so missing files fail startup unless the runtime supplies them.

### Kubernetes Profile

`application-kubernetes.yml` sets:

| Setting | Value | Purpose |
| --- | --- | --- |
| `import-path` | `/etc/.credentials` | Location where external secrets are projected. |
| `spring.main.banner-mode` | `off` | Quiet startup banner. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Restricts actuator exposure. |
| `management.endpoint.health.probes.enabled` | `true` | Enables Kubernetes liveness/readiness probes. |
| `management.endpoint.health.group.liveness.include` | `livenessState` | Liveness group contents. |
| `management.endpoint.health.group.readiness.include` | `readinessState,db,imageHostingReadiness` | Readiness group contents. |
| `management.tracing.sampling.probability` | `1.0` | Samples all traces when tracing is enabled by the runtime. |
| `kafka.properties.security.protocol` | `SASL_PLAINTEXT` | Cluster-internal Kafka security protocol override. |
| `lego.minio.url` | `http://minio.minio-dev.svc.cluster.local` | Cluster-internal MinIO URL. |

## REST Controllers

All current business controllers are internal image-hosting controllers under `/internal/image-hosting`. There are no request bodies; inputs are path variables and query parameters.

Detailed response examples and workflow guidance for the image-hosting endpoints are also maintained in [docs/image-hosting-api.md](docs/image-hosting-api.md).

### Provider Selection Parameters

Most image-hosting endpoints accept the same provider selection parameters.

| Parameter | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- |
| `provider` | No | `lego.image-hosting.default-provider` | A configured key under `lego.image-hosting.providers`, currently `flickr` | Selects provider-specific defaults. |
| `externalServiceId` | No | Provider `external-service-id`, otherwise `lego.image-hosting.sync.external-service-id` | Integer matching an `external_service` row | Overrides the external service id used for DB lookups/writes. |

### Remote Read Parameters

Remote snapshot, sync-plan, and repair-plan endpoints can read remote provider state.

| Parameter | Required | Default | Valid values | Description |
| --- | --- | --- | --- | --- |
| `userId` | No | Provider/client configuration where supported | Flickr user id, for example `144144385@N08` | Explicit remote account/user selection. |
| `albumPageSize` | No | `500` | Positive integer accepted by the provider | Page size for remote album listing. |
| `photoPageSize` | No | `500` | Positive integer accepted by the provider | Page size for remote album photo listing. |

### Controller Summary

| Controller | Method | Path | Writes remote provider | Writes DB | Purpose |
| --- | --- | --- | --- | --- | --- |
| `ImageHostingSyncController` | `POST` | `/internal/image-hosting/item-inventories/{itemInventoryId}/sync` | Yes when `dryRun=false` | Yes when `dryRun=false` | Legacy direct image-hosting sync workflow for one item inventory. Prefer sync-plan endpoints for controlled operations. |
| `GeneratedDescriptionController` | `GET` | `/internal/image-hosting/item-inventories/{itemInventoryId}/generated-description` | No | No | Preview the generated DB-backed title/description used by image-hosting sync. |
| `ImageHostingRemoteSnapshotController` | `GET` | `/internal/image-hosting/remote/albums/{albumId}/snapshot` | No | No | Read the current remote album/photos state for inspection. |
| `ImageHostingSyncPlanController` | `GET` | `/internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan` | No | No | Build a dry-run reconciliation plan comparing DB desired state to remote image-hosting state. |
| `ImageHostingSyncPlanController` | `POST` | `/internal/image-hosting/item-inventories/{itemInventoryId}/sync-plan/apply` | Yes, depending on plan actions | Yes, depending on plan actions | Rebuild and apply the current sync plan. |
| `ImageHostingDbRepairController` | `GET` | `/internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan` | No | No | Build a DB repair plan from current remote image-hosting state. |
| `ImageHostingDbRepairController` | `POST` | `/internal/image-hosting/item-inventories/{itemInventoryId}/repair-plan/apply` | Usually no, but safe provider membership operations may occur | Yes | Apply DB link repair actions. |

### Endpoint Parameters

| Endpoint | Additional parameters |
| --- | --- |
| `POST /item-inventories/{itemInventoryId}/sync` | `dryRun` defaults to `true`; `retryFailed` defaults to `false`; also accepts `provider` and `externalServiceId`. |
| `GET /item-inventories/{itemInventoryId}/generated-description` | Accepts `provider` and `externalServiceId`. |
| `GET /remote/albums/{albumId}/snapshot` | Accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`. |
| `GET /item-inventories/{itemInventoryId}/sync-plan` | Accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`. |
| `POST /item-inventories/{itemInventoryId}/sync-plan/apply` | Same as sync-plan plus `allowReviewRequired`, default `false`. |
| `GET /item-inventories/{itemInventoryId}/repair-plan` | Accepts `provider`, `externalServiceId`, `userId`, `albumPageSize`, `photoPageSize`. |
| `POST /item-inventories/{itemInventoryId}/repair-plan/apply` | Same as repair-plan plus `allowReviewRequired`, default `false`. |

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

| Behavior | Detail |
| --- | --- |
| Plan-first safety | `POST /sync-plan/apply` rebuilds the plan before applying. Re-check if remote state may have changed since the last `GET /sync-plan`. |
| Review-required actions | Blocked unless `allowReviewRequired=true`. Do not use this switch blindly in automation. |
| Scheduled job integration | The scheduled image-hosting job uses the same planner/executor path. |
| Repair endpoints | Use when Flickr already has the album/photos but DB rows in `external_image`, `external_image_album`, or `external_image_album_image` are missing or stale. |

## Scheduled Jobs

Scheduling is enabled globally by `io.legohunter.ingress.scheduling.config.SchedulingConfiguration` using `@EnableScheduling`. ShedLock is configured by `SchedulerConfiguration`, so scheduled jobs should have lock rows available in the database configured by the application.

### `BricklinkOpenOrderProbeJob`

Class: `io.legohunter.ingress.source.bricklink.orders.BricklinkOpenOrderProbeJob`

Condition:

```yaml
lego.bricklink.orders.sync.scheduled.enabled: true
```

Schedule:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.orders.sync.scheduled.fixed-delay-ms` | `300000` in code, `60000` in base YAML | Long milliseconds, >= 0 | Delay between the end of one run and the start of the next. |
| `lego.bricklink.orders.sync.scheduled.initial-delay-ms` | `30000` in code, `5000` in base YAML | Long milliseconds, >= 0 | Delay after app startup before first run. |
| `lego.bricklink.orders.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration, for example `30s`, `10m`, `1h` | Maximum distributed lock duration. |
| `lego.bricklink.orders.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |

Operational modes:

| `apply` | Behavior |
| --- | --- |
| `false` | Probe mode. Authenticates to BrickLink, lists configured open order statuses, fetches order details and items, logs coverage/counters, writes no marketplace sync rows. |
| `true` | Write-side sync mode. Creates a sync-run row, upserts marketplace orders, syncs marketplace order items, stores raw payload audit rows, and deletes stale item rows no longer present in the latest response. |

Data written when `apply=true`:

| Table | Write behavior |
| --- | --- |
| `marketplace_order_sync_run` | Inserted at run start as `STARTED`; updated at completion as `SUCCESS`, `PARTIAL_FAILURE`, `FAILED`, or `NO_WORK`. |
| `marketplace_order` | Upserted by marketplace/order natural key. Tracks status, buyer/shipping/payment/cost fields, counts, weights, flags, payload hash, and last seen time. |
| `marketplace_order_item` | Existing rows are updated by stable generated external order item id; new rows are inserted; stale rows for the order are deleted. |
| `marketplace_order_payload` | Inserts raw JSON audit payloads for order detail and order items responses with SHA-256 hashes. |

BrickLink API calls per run:

| Step | Description |
| --- | --- |
| List orders | Calls order list endpoint once per configured status. |
| Include cancelled | If `include-unfiled-cancelled=true`, also lists `CANCELLED` with `filed=false` unless already included in `statuses`. |
| Fetch detail | Calls get-order detail for each unique discovered order id. |
| Fetch items | Calls get-order-items for each fetched order. |

Important logs:

| Event | Meaning |
| --- | --- |
| `bricklink.order_sync.probe.started` | Job started with configured direction/statuses/apply mode. |
| `bricklink.order_sync.probe.order_query_failed` | A status query failed; job continues with other statuses. |
| `bricklink.order_sync.probe.order_fetched` | One order detail and item list were fetched. |
| `bricklink.order_sync.probe.coverage` | Field coverage for one fetched order. |
| `bricklink.order_sync.probe.item_coverage` | Field coverage across fetched order items. |
| `bricklink.order_sync.probe.completed` | Job completed with counts and elapsed time. |
| `bricklink.order_sync.probe.failed` | Job-level failure. |

### `ImageHostingScheduledSyncJob`

Class: `io.legohunter.egress.imagehosting.ImageHostingScheduledSyncJob`

Condition:

```yaml
lego.image-hosting.sync.scheduled.enabled: true
```

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between job runs. |
| `lego.image-hosting.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.image-hosting.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.image-hosting.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.image-hosting.sync.scheduled.batch-size` | `25` | Integer; effective value is at least `1` | Maximum candidate item inventories selected per run. |
| `lego.image-hosting.sync.scheduled.concurrency` | `2` in code, `5` in base YAML, `2` in Kubernetes | Integer; effective value is at least `1` | Thread pool size for inventory sync work. |
| `lego.image-hosting.sync.scheduled.retry-failed` | `true` | `true`, `false` | Whether failed sync rows are eligible for selection. |
| `lego.image-hosting.sync.scheduled.apply` | `false` | `true`, `false` | If `false`, build/log plans only. If `true`, execute applicable plan actions. |

Candidate reasons logged by the job:

| Reason | Meaning |
| --- | --- |
| `missingAlbumLink` | Inventory item needs an external album link. |
| `missingPhotoLink` | One or more photos need external image links. |
| `failedSync` | Prior sync rows failed and retry is enabled. |
| `pendingSync` | Rows are pending sync. |
| `metadataChanged` | Local metadata differs from metadata last synced to the provider. |

Outcomes:

| Outcome | Meaning |
| --- | --- |
| `NO_WORK` | No candidate item inventories selected. |
| `SUCCESS` | Candidates were processed and none failed. |
| `PARTIAL_FAILURE` | At least one candidate succeeded and at least one failed. |
| `FAILED` | All selected candidates failed. |

Important logs:

| Event | Meaning |
| --- | --- |
| `image_hosting.sync_job.started` | Job started with provider, batch size, concurrency, retry, and apply mode. |
| `image_hosting.sync_job.candidates_selected` | Candidate count breakdown by reason. |
| `image_hosting.sync_job.no_work` | No candidates selected. |
| `image_hosting.sync_job.inventory_no_actions` | Plan had no actions for one inventory item. |
| `image_hosting.sync_job.inventory_planned` | Apply is false and actions were planned but not executed. |
| `image_hosting.sync_job.inventory_completed` | One inventory item completed in apply mode. |
| `image_hosting.sync_job.inventory_failed` | One inventory item failed; job continues. |
| `image_hosting.sync_job.completed` | Run-level result. |

### `FulfillmentScheduledSyncJob`

Class: `io.legohunter.egress.fulfillment.FulfillmentScheduledSyncJob`

Condition:

```yaml
lego.fulfillment.sync.scheduled.enabled: true
```

Schedule and selection settings:

| Setting | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.fulfillment.sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between job runs. |
| `lego.fulfillment.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | Delay after startup before first run. |
| `lego.fulfillment.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration | Maximum distributed lock duration. |
| `lego.fulfillment.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration | Minimum distributed lock duration. |
| `lego.fulfillment.sync.scheduled.batch-size` | `25` | Integer; effective value is at least `1` | Maximum marketplace order candidates selected per run. |
| `lego.fulfillment.sync.scheduled.apply` | `false` | `true`, `false` | If `false`, maps staged orders and records counters only. If `true`, performs ShipStation and BrickLink write-side actions. |

Candidate source:

| Source | Detail |
| --- | --- |
| `marketplace_order` | Selected through `MarketplaceOrderDao.findFulfillmentCandidates` by configured marketplace code and statuses. |
| `marketplace_order_payload` | Uses latest `ORDER_RESPONSE` and `ORDER_ITEMS_RESPONSE` raw JSON payloads as the canonical source for ShipStation mapping. Missing payloads are counted and skipped. |
| `marketplace_order_item` | Used to resolve external order item ids and linked local inventory for order item images. |
| `item_inventory_photo` and `external_image` | Used to assign ShipStation item image URLs from the configured image external service. |

Operational modes:

| `apply` | Behavior |
| --- | --- |
| `false` | Dry-run mode. Loads staged BrickLink payloads, maps ShipStation orders, resolves item image URLs, logs actions as skipped, and makes no ShipStation or BrickLink API calls. |
| `true` | Apply mode. Looks up each ShipStation order by `BL-{bricklinkOrderId}`. Creates missing ShipStation orders, updates existing non-shipped ShipStation orders, and reconciles already-shipped ShipStation orders back to BrickLink. |

ShipStation order mapping capabilities:

| Capability | Detail |
| --- | --- |
| Order identity | `orderNumber` and `orderKey` use `lego.fulfillment.shipstation.order-number-prefix`, default `BL-`, plus BrickLink order id. |
| Status | Cancelled BrickLink orders map to ShipStation `cancelled`; paid BrickLink orders are promoted to paid in ShipStation; otherwise initial status is awaiting payment. |
| Address and customer fields | Maps buyer username/email, bill-to, ship-to, requested shipping service, payment method, and internal notes from staged BrickLink order JSON. |
| Amounts | Maps order total, tax, shipping plus insurance amount, and item unit prices. |
| Weight | Maps order and item weights in grams where present. |
| Shipping service | Uses domestic or international ShipStation service code based on destination country. `UK` is normalized to `GB`. |
| Insurance | Always creates ShipStation insurance options with configured provider and insured item value. |
| International options | For non-domestic destinations, creates customs options with configured contents, non-delivery behavior, country of origin, and one customs item per order item. |
| Item images | Uses the primary local inventory photo's external image URL first; if none is available, uses any available local inventory photo external image URL; if no external image URL exists, leaves the ShipStation item image unset. |

Apply-mode ShipStation and BrickLink behavior:

| Case | Behavior |
| --- | --- |
| No ShipStation order exists | Calls `ShipStationRestClient.createOrUpdateOrder` without an order id and counts `ordersCreated`. |
| One non-shipped ShipStation order exists | Copies the existing ShipStation order id onto the mapped order, calls `createOrUpdateOrder`, and counts `ordersUpdated`. |
| One shipped ShipStation order exists | Fetches ShipStation shipments, selects the first non-voided shipment with a tracking number, updates BrickLink tracking data, marks BrickLink order `SHIPPED`, sends drive-thru when BrickLink has not already done so, and counts `ordersShippedReconciled`. |
| Multiple exact ShipStation order number matches | Fails that candidate because the job cannot safely choose which remote order to mutate. |
| Shipment missing for shipped order | Fails that candidate because BrickLink cannot be reconciled without tracking. |

Tracking URL rules:

| Destination | Tracking URL |
| --- | --- |
| ShipStation ship-to country `US` | `https://tools.usps.com/go/TrackConfirmAction.action?tLabels={trackingNumber}` |
| Any other destination | `http://parcelsapp.com/en/tracking/{trackingNumber}` |

Outcomes:

| Outcome | Meaning |
| --- | --- |
| `NO_WORK` | No fulfillment candidates selected. |
| `SUCCESS` | Candidates were processed and none failed. |
| `PAYLOADS_MISSING` | Candidates were selected, but none could be mapped because required staged payloads were missing. |
| `FAILED` | All mapped/loaded candidates failed. |
| `PARTIAL_FAILURE` | At least one candidate succeeded and at least one failed. |

Important logs:

| Event | Meaning |
| --- | --- |
| `fulfillment.sync_job.started` | Job started with provider, marketplace, statuses, batch size, and apply mode. |
| `fulfillment.sync_job.no_work` | No fulfillment candidates selected. |
| `fulfillment.sync_job.payload_missing` | Candidate did not have both required staged raw payloads. |
| `fulfillment.sync_job.order_mapped` | Candidate mapped to a ShipStation order and records the planned or applied action. |
| `fulfillment.sync_job.shipstation_synced` | ShipStation create/update returned successfully. |
| `fulfillment.sync_job.bricklink_shipped_reconciled` | Shipped ShipStation order was reconciled back to BrickLink. |
| `fulfillment.sync_job.order_failed` | One candidate failed; job continues with remaining candidates. |
| `fulfillment.sync_job.completed` | Run-level result and counters. |

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

| Topic config id | Consumer factory bean | Kafka template bean | Container factory bean |
| --- | --- | --- | --- |
| `upload-photo` | `uploadPhotoConsumerFactory` | `uploadPhotoKafkaTemplate` if producer configured | `uploadPhotoContainerFactory` |
| `bricklink-catalog-entry` | `bricklinkCatalogEntryConsumerFactory` | `bricklinkCatalogEntryKafkaTemplate` if producer configured | `bricklinkCatalogEntryContainerFactory` |

Rules:

| Configuration | Behavior |
| --- | --- |
| `kafka.topic-configuration.<id>.consumer` present | Registers a consumer factory and listener container factory. |
| `kafka.topic-configuration.<id>.producer` present | Registers a producer factory and Kafka template. |
| Hyphenated Kafka property keys | Converted to dotted Kafka client keys, for example `max-poll-records` becomes `max.poll.records`. |
| Sensitive properties | `sasl.jaas.config`, SSL passwords, and key passwords are masked in registrar logs. |

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

| Event/key | Routed to topic setting | Notes |
| --- | --- | --- |
| `s3:ObjectCreated:*`, key starts `photos/` and not `photos/rejected/` or `photos/duplicate/` | `kafka.topic-configuration.upload-photo.topic` | Photo processing pipeline. |
| `s3:ObjectCreated:*`, key starts `bricklink/catalog/` | `kafka.topic-configuration.upload-bricklink-catalog.topic` | BrickLink catalog XML pipeline. |
| `s3:ObjectCreated:*`, key starts `bricklink/category/` | `kafka.topic-configuration.upload-bricklink-category.topic` | BrickLink category XML pipeline. |
| `s3:ObjectCreated:*`, key starts `rebrickable/catalog/` | `kafka.topic-configuration.upload-rebrickable-catalog.topic` | Rebrickable catalog CSV gzip pipeline. |
| `s3:ObjectCreated:*`, key starts `rebrickable/category/` | `kafka.topic-configuration.upload-rebrickable-theme.topic` | Rebrickable theme CSV gzip pipeline. |
| `s3:ObjectRemoved:*`, bucket `lego-photos-sandbox`, key shape `<dir>/<dir>/<32-hex-md5>.jpg` | `kafka.topic-configuration.delete-photo.topic` | Final photo deletion pipeline. |

Unsupported object events are logged as warnings and are not routed.

### Kafka Pipeline Summary

| Pipeline | Listener | Input | Output/write |
| --- | --- | --- | --- |
| Photo upload | `PhotoUploadS3EventListener` | `upload-photo.topic` | Calls `PhotoProcessingService.process(PhotoUploadEvent)`. |
| Photo delete | `PhotoDeletedS3EventListener` | `delete-photo.topic` | Calls `PhotoDeletionService.process(ObjectDeletedEvent)`. |
| BrickLink catalog file split | `BricklinkCatalogS3EventListener` | `upload-bricklink-catalog.topic` | Reads XML from MinIO and publishes one `CatalogEntry` per `ITEM` to `bricklink-catalog-entry.topic`. |
| BrickLink catalog DB upsert | `BricklinkCatalogEntryConsumer` | `bricklink-catalog-entry.topic` | Upserts `external_catalog_item` and category link when category exists. |
| BrickLink category file split | `BricklinkCategoryS3EventListener` | `upload-bricklink-category.topic` | Reads XML from MinIO and publishes one `CategoryEntry` per `ITEM` to `bricklink-category-entry.topic`. |
| BrickLink category DB upsert | `BricklinkCategoryEntryConsumer` | `bricklink-category-entry.topic` | Upserts `external_category`. |
| Rebrickable catalog file split | `RebrickableCatalogS3EventListener` | `upload-rebrickable-catalog.topic` | Reads gzipped CSV and publishes one `RebrickableCatalogEntry` per row to `rebrickable-catalog-entry.topic`. |
| Rebrickable catalog DB upsert | `RebrickableCatalogEntryConsumer` | `rebrickable-catalog-entry.topic` | Upserts `external_catalog_item` and category link when category exists. |
| Rebrickable theme file split | `RebrickableThemeS3EventListener` | `upload-rebrickable-theme.topic` | Reads gzipped CSV and publishes one `RebrickableThemeEntry` per row to `rebrickable-theme-entry.topic`. |
| Rebrickable theme DB upsert | `RebrickableThemeEntryConsumer` | `rebrickable-theme-entry.topic` | Upserts `external_category` and resolves parent category when present. |

Notes:

| Area | Detail |
| --- | --- |
| Photo upload concurrency | Configurable through `kafka.topic-configuration.upload-photo.consumer.concurrency`, default `1`. |
| Photo delete concurrency | Hard-coded to `6` in the listener annotation; not currently YAML-configurable. |
| BrickLink external service id | Catalog/category consumers currently hard-code `2`. |
| Rebrickable external service id | Catalog/theme consumers currently hard-code `9`. |
| Rebrickable catalog category lookup | The current catalog category lookup uses external service id `2`; review before relying on Rebrickable category links. |

Expected Rebrickable CSV headers:

| File type | Headers |
| --- | --- |
| Catalog | `SET_NUM`, `NAME`, `YEAR`, `THEME_ID`, `NUM_PARTS`, `IMG_URL` |
| Theme/category | `ID`, `NAME`, `PARENT_ID` |

## Configuration Reference

### `lego.bricklink.orders.sync.*`

Backed by `BricklinkOrderSyncProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.bricklink.orders.sync.marketplace-code` | `BRICKLINK` | Non-blank string; normalized to uppercase | Marketplace code written to marketplace sync tables. |
| `lego.bricklink.orders.sync.metrics-tag` | `bricklink` | Non-blank string | Low-cardinality provider tag for metrics. |
| `lego.bricklink.orders.sync.direction` | `in` | BrickLink API direction, normally `in` for seller/inbound orders or `out` for buyer/outbound orders | Direction sent to BrickLink order list calls and written to marketplace orders. |
| `lego.bricklink.orders.sync.statuses` | `PENDING`, `UPDATED`, `READY`, `PROCESSING`, `PAID`, `PACKED` | BrickLink API order status strings; code trims, uppercases, and de-duplicates | Statuses queried every run. |
| `lego.bricklink.orders.sync.include-unfiled-cancelled` | `true` | `true`, `false` | Also query unfiled cancelled orders using `filed=false&status=CANCELLED`. |
| `lego.bricklink.orders.sync.scheduled.enabled` | `false` in code, `true` in base YAML | `true`, `false` | Creates scheduled job/service beans when true. |
| `lego.bricklink.orders.sync.scheduled.apply` | `false` | `true`, `false` | Enables database writes when true. Keep false for probe-only validation. |
| `lego.bricklink.orders.sync.scheduled.fixed-delay-ms` | `300000` in code, `60000` in base YAML | Long milliseconds, >= 0 | Delay between runs. |
| `lego.bricklink.orders.sync.scheduled.initial-delay-ms` | `30000` in code, `5000` in base YAML | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.bricklink.orders.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.bricklink.orders.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

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

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `bricklink.rest.uri` | None in properties; `https://api.bricklink.com/api/store/v1` in base YAML | Absolute URI | BrickLink REST base URI. Use `/api/store/v1`, not `/v2`. |
| `bricklink.rest.consumer.key` | None | BrickLink API consumer key | OAuth consumer key. Secret, externalized. |
| `bricklink.rest.consumer.secret` | None | BrickLink API consumer secret | OAuth consumer secret. Secret, externalized. |
| `bricklink.rest.token.value` | None | BrickLink API token value | OAuth access token. Secret, externalized. |
| `bricklink.rest.token.secret` | None | BrickLink API token secret | OAuth token secret. Secret, externalized. |
| `bricklink.rest.http-logging.enabled` | `false` in dependency, `true` in base YAML | `true`, `false` | Adds request/response logging interceptor when true. |
| `bricklink.rest.http-logging.include-headers` | `false` in dependency, `true` in base YAML | `true`, `false` | Includes headers in debug logs. Sensitive headers/cookies are redacted by the interceptor. |
| `bricklink.rest.http-logging.include-body` | `true` | `true`, `false` | Includes request/response body in debug logs. |
| `bricklink.rest.http-logging.max-body-length` | `-1` | `-1` for unlimited, or non-negative max characters | Truncates logged bodies when non-negative. |

Operational notes:

| Symptom | Likely cause | Action |
| --- | --- | --- |
| HTTP `302` to `/v2/error_404.page` | Wrong base URI such as `https://api.bricklink.com/v2` | Set `bricklink.rest.uri` to `https://api.bricklink.com/api/store/v1`. |
| `401`/OAuth errors | Missing or invalid consumer/token secrets | Check `${import-path}/bricklink-client-api-keys.yml`. |
| Very large logs | `include-body=true` and `max-body-length=-1` | Set a finite max body length or disable body logging. |

### `lego.fulfillment.*`

Backed by `FulfillmentSyncProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.fulfillment.marketplace-code` | `BRICKLINK` | Non-blank string; normalized to uppercase | Marketplace code used when selecting staged marketplace orders. |
| `lego.fulfillment.metrics-tag` | `fulfillment` | Non-blank string | Low-cardinality provider tag for metrics. |
| `lego.fulfillment.statuses` | `PENDING`, `UPDATED`, `READY`, `PROCESSING`, `PAID`, `PACKED` | Marketplace order status strings; code trims, uppercases, and de-duplicates | Candidate statuses selected from local marketplace staging tables. |
| `lego.fulfillment.sync.scheduled.enabled` | `false` | `true`, `false` | Creates fulfillment scheduled job/service beans when true. |
| `lego.fulfillment.sync.scheduled.apply` | `false` | `true`, `false` | Enables ShipStation and BrickLink write-side calls when true. Keep false for mapping validation. |
| `lego.fulfillment.sync.scheduled.batch-size` | `25` | Integer; effective value at least `1` | Maximum candidate orders per run. |
| `lego.fulfillment.sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between runs. |
| `lego.fulfillment.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.fulfillment.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.fulfillment.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |
| `lego.fulfillment.shipstation.order-number-prefix` | `BL-` | String | Prefix used to generate/find ShipStation order numbers. |
| `lego.fulfillment.shipstation.domestic-country-code` | `US` | ISO-style country code | Destination country treated as domestic for service and tracking URL decisions. |
| `lego.fulfillment.shipstation.carrier-code` | `stamps_com` | ShipStation carrier code | Carrier assigned to mapped orders. |
| `lego.fulfillment.shipstation.domestic-service-code` | `usps_priority_mail` | ShipStation service code | Service assigned to domestic orders. |
| `lego.fulfillment.shipstation.international-service-code` | `usps_priority_mail_international` | ShipStation service code | Service assigned to international orders. |
| `lego.fulfillment.shipstation.package-code` | `package` | ShipStation package code | Package assigned to mapped orders. |
| `lego.fulfillment.shipstation.insurance-provider` | `shipsurance` | ShipStation insurance provider | Provider used in ShipStation insurance options. |
| `lego.fulfillment.shipstation.international-contents` | `merchandise` | ShipStation international contents value | Contents value used for international customs options. |
| `lego.fulfillment.shipstation.international-non-delivery` | `return_to_sender` | ShipStation international non-delivery value | Non-delivery behavior used for international customs options. |
| `lego.fulfillment.shipstation.customs-country-of-origin` | `US` | ISO-style country code | Country of origin assigned to customs items. |
| `lego.fulfillment.shipstation.order-item-image-external-service-id` | `10` | Integer external service id | External image service used when resolving ShipStation order item images. |

Recommended safe defaults:

```yaml
lego:
  fulfillment:
    marketplace-code: BRICKLINK
    metrics-tag: fulfillment
    statuses: [PENDING, UPDATED, READY, PROCESSING, PAID, PACKED]
    sync:
      scheduled:
        enabled: true
        apply: false
```

### `shipstation.rest.*`

Backed by fulfillment's ShipStation REST configuration.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `shipstation.rest.uri` | `https://ssapi.shipstation.com` | Absolute URI | ShipStation REST base URI. |
| `shipstation.rest.api-key` | None | ShipStation API key | Required to create the ShipStation client. Secret, externalized. |
| `shipstation.rest.api-secret` | None | ShipStation API secret | Required to create the ShipStation client. Secret, externalized. |

Operational notes:

| Mode | Required external clients |
| --- | --- |
| `lego.fulfillment.sync.scheduled.apply=false` | No ShipStation client and no BrickLink mutation client are required by the fulfillment job. |
| `lego.fulfillment.sync.scheduled.apply=true` | ShipStation credentials are required for all candidate writes. BrickLink credentials are required when a shipped ShipStation order must be reconciled back to BrickLink. |

### `lego.image-hosting.*`

Backed by `ImageHostingSyncProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.default-provider` | `flickr` | Key under `lego.image-hosting.providers` | Provider used when request/job omits `provider`. |
| `lego.image-hosting.providers.<provider>.enabled` | `false` in code, `true` for Flickr in base YAML | `true`, `false` | Enables provider configuration. Readiness expects Flickr enabled when sync runtime is required. |
| `lego.image-hosting.providers.<provider>.external-service-id` | None | Integer external service id | DB external service id for provider rows. Flickr is currently `10`. |
| `lego.image-hosting.providers.<provider>.display-name` | Provider key | String | Human-readable provider name. |
| `lego.image-hosting.providers.<provider>.metrics-tag` | Provider key | Low-cardinality string | Metrics provider tag. |
| `lego.image-hosting.sync.external-service-id` | `10` | Integer | Fallback external service id when provider config does not supply one. |
| `lego.image-hosting.sync.temp-directory` | `${java.io.tmpdir}/lego-data-ingress-image-hosting` | Filesystem path | Temporary directory used when adapting S3 objects to file-path based provider APIs. |

#### Image-Hosting Retry

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.sync.retry.enabled` | `true` | `true`, `false` | Enables provider retry wrapper. If false, effective max attempts is `1`. |
| `lego.image-hosting.sync.retry.max-attempts` | `3` | Integer; effective value at least `1` | Maximum attempts. |
| `lego.image-hosting.sync.retry.initial-backoff-ms` | `500` | Long milliseconds; effective value at least `0` | Initial retry delay. |
| `lego.image-hosting.sync.retry.backoff-multiplier` | `2.0` | Double; effective value at least `1.0` | Exponential backoff multiplier. |
| `lego.image-hosting.sync.retry.max-backoff-ms` | `5000` | Long milliseconds; effective value at least initial backoff | Maximum retry delay. |

#### Image-Hosting Scheduled Sync

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.sync.scheduled.enabled` | `false` | `true`, `false` | Enables scheduled image-hosting sync job. |
| `lego.image-hosting.sync.scheduled.batch-size` | `25` | Integer; effective value at least `1` | Candidate item inventory limit per run. |
| `lego.image-hosting.sync.scheduled.concurrency` | `2` in code, `5` in base YAML, `2` in Kubernetes | Integer; effective value at least `1` | Worker thread count. |
| `lego.image-hosting.sync.scheduled.retry-failed` | `true` | `true`, `false` | Includes failed sync rows in candidate query. |
| `lego.image-hosting.sync.scheduled.apply` | `false` | `true`, `false` | If false, plans only. If true, executes plan actions. |
| `lego.image-hosting.sync.scheduled.fixed-delay-ms` | `300000` | Long milliseconds, >= 0 | Delay between runs. |
| `lego.image-hosting.sync.scheduled.initial-delay-ms` | `30000` | Long milliseconds, >= 0 | First-run startup delay. |
| `lego.image-hosting.sync.scheduled.lock-at-most-for` | `10m` | ShedLock duration string | Maximum distributed lock time. |
| `lego.image-hosting.sync.scheduled.lock-at-least-for` | `0s` | ShedLock duration string | Minimum distributed lock time. |

#### Image-Hosting Publishing

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.publishing.photo.title-template` | `{captionOrFilename}` | Template string | Photo title template used for provider publishing. |
| `lego.image-hosting.publishing.photo.description-template` | `{captionOrTitle}` | Template string | Photo description template. |
| `lego.image-hosting.publishing.photo.tags` | `[]` | List of strings | Provider tags added to photos. |
| `lego.image-hosting.publishing.photo.public-flag` | `true` | `true`, `false` | Flickr public visibility flag. |
| `lego.image-hosting.publishing.photo.friend-flag` | `false` | `true`, `false` | Flickr friend visibility flag. |
| `lego.image-hosting.publishing.photo.family-flag` | `false` | `true`, `false` | Flickr family visibility flag. |
| `lego.image-hosting.publishing.photo.hidden` | `false` | `true`, `false` | Provider hidden flag. |
| `lego.image-hosting.publishing.photo.safety-level` | `safe` | Provider-supported safety value, currently `safe` by default | Flickr safety level. |

#### Image-Hosting Readiness

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.image-hosting.readiness.enabled` | `true` | `true`, `false` | Enables `imageHostingReadiness` health indicator and startup readiness logs. |
| `lego.image-hosting.readiness.require-scheduled-sync-enabled` | `false` in base YAML, `true` in Kubernetes | `true`, `false` | If true, readiness is DOWN when scheduled sync is disabled. |
| `lego.image-hosting.readiness.require-bitly-when-apply-enabled` | `true` | `true`, `false` | If true, Bitly config is required when scheduled sync and apply are both enabled. |

### `flickr.*`

Backed by `lego-imaging` `FlickrProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `flickr.application-name` | None | String | Application name passed to Flickr integration where used. |
| `flickr.debug-request` | `false` when omitted | `true`, `false` | Enables Flickr request debug mode in `lego-imaging`. |
| `flickr.debug-stream` | `false` when omitted | `true`, `false` | Enables Flickr stream debug mode in `lego-imaging`. |
| `flickr.user-id` | None | Flickr user id | Required by image-hosting readiness when sync runtime is required. |
| `flickr.secrets.key` | None | Secret string | Flickr API key. |
| `flickr.secrets.secret` | None | Secret string | Flickr API secret. |
| `flickr.secrets.token` | None | Secret string | Flickr OAuth token. |
| `flickr.secrets.token-secret` | None | Secret string | Flickr OAuth token secret. |

### `bitly.*`

Backed by `lego-imaging` `BitlyProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `bitly.base-url` | `https://api-ssl.bitly.com/v4` | Absolute URI | Bitly API base URL. |
| `bitly.access-token` | None | Secret string | Bitly access token. Required for short URL operations. |
| `bitly.group-guid` | None | Bitly group GUID | Required for short URL operations. |
| `bitly.oauth2.client-id` | None | Secret string | OAuth2 client id, currently not used by the readiness check. |
| `bitly.oauth2.client-secret` | None | Secret string | OAuth2 client secret, currently not used by the readiness check. |

### `lego.minio.*`

Backed by `S3ClientProperties`.

| Property | Default | Valid values | Description |
| --- | --- | --- | --- |
| `lego.minio.url` | Local/Kubernetes profile sets environment-specific URL | Absolute URI | MinIO/S3 endpoint. |
| `lego.minio.access-key` | None | Secret string | MinIO access key. |
| `lego.minio.secret-key` | None | Secret string | MinIO secret key. |

`lego.minio.url`, `access-key`, and `secret-key` are required by image-hosting readiness when image-hosting sync runtime is required.

### `kafka.*`

Backed by `KafkaCustomProperties` and Spring Kafka properties.

| Property | Default/Example | Valid values | Description |
| --- | --- | --- | --- |
| `kafka.bootstrap-servers` | Redpanda dev brokers | List of host:port strings | Kafka bootstrap servers. |
| `kafka.client-id` | None | String | Optional Kafka client id. |
| `kafka.properties.*` | Security and SASL settings | Kafka client properties | Common properties applied to all producers/consumers. |
| `kafka.consumer-defaults.*` | Sandbox defines deserializers/auth | Spring Kafka consumer properties | Defaults merged into every topic consumer. |
| `kafka.producer-defaults.*` | Sandbox defines serializers/retries/auth | Spring Kafka producer properties | Defaults merged into every topic producer. |
| `kafka.topic-configuration.<id>.topic` | Topic-specific | Single topic name | Used by most listeners/templates. |
| `kafka.topic-configuration.<id>.topics` | Topic-specific | Comma-separated or YAML list of topic names | Used by listeners that subscribe to multiple topics, such as `minio-s3`. |
| `kafka.topic-configuration.<id>.consumer.group-id` | Topic-specific | String | Consumer group id. |
| `kafka.topic-configuration.<id>.consumer.concurrency` | Topic-specific | Integer | Used only where the listener annotation references it. Currently `upload-photo`. |
| `kafka.topic-configuration.<id>.consumer.properties.*` | Topic-specific | Kafka/Spring Kafka properties | Per-topic consumer overrides. Hyphens are converted to dots. |
| `kafka.topic-configuration.<id>.producer.*` | Topic-specific | Kafka producer properties | Per-topic producer overrides. Hyphens are converted to dots. |

Sandbox topic ids currently configured:

| Topic id | Role |
| --- | --- |
| `minio-s3` | Raw MinIO S3 event input. |
| `upload-minio-s3` | Producer for routed upload/delete events. |
| `upload-photo` | Routed photo upload events. |
| `delete-photo` | Routed photo delete events. |
| `upload-bricklink-catalog` | Routed BrickLink catalog file events. |
| `upload-bricklink-category` | Routed BrickLink category file events. |
| `upload-rebrickable-catalog` | Routed Rebrickable catalog file events. |
| `upload-rebrickable-theme` | Routed Rebrickable theme/category file events. |
| `photo-upload-entry` | Configured but no current main-code listener found. |
| `bricklink-catalog-entry` | Parsed BrickLink catalog entries. |
| `bricklink-category-entry` | Parsed BrickLink category entries. |
| `rebrickable-catalog-entry` | Parsed Rebrickable catalog entries. |
| `rebrickable-theme-entry` | Parsed Rebrickable theme entries. |

## Readiness And Actuator

### Actuator URLs

| Endpoint | Local profile | Kubernetes profile | Purpose |
| --- | --- | --- | --- |
| `/actuator/health` | Exposed | Exposed | Overall health. |
| `/actuator/health/liveness` | Exposed with all local endpoints | Exposed | Kubernetes liveness. |
| `/actuator/health/readiness` | Exposed with all local endpoints | Exposed | Kubernetes readiness including DB and image-hosting readiness. |
| `/actuator/prometheus` | Exposed with all local endpoints | Exposed | Prometheus scrape endpoint. |
| `/actuator/metrics` | Exposed with all local endpoints | Exposed | Metrics endpoint. |
| `/swagger-ui/index.html` | Exposed by application | Exposed unless restricted externally | OpenAPI UI. |
| `/v3/api-docs` | Exposed by application | Exposed unless restricted externally | OpenAPI JSON. |

### `imageHostingReadiness`

Enabled when:

```yaml
lego.image-hosting.readiness.enabled: true
management.health.imageHostingReadiness.enabled: true
```

Checks:

| Component | Required when | Validates |
| --- | --- | --- |
| `scheduled-sync` | Required if `require-scheduled-sync-enabled=true`; otherwise warning when disabled | Scheduled sync enabled, batch size > 0, concurrency > 0. |
| `database` | Required if scheduled sync is enabled or required | `spring.datasource.database-key-name` and `DataSource` bean. |
| `s3` | Required if scheduled sync is enabled or required | `lego.minio.url`, `lego.minio.access-key`, `lego.minio.secret-key`. |
| `flickr` | Required if scheduled sync is enabled or required | Flickr provider config, external service id, user id, and secrets. |
| `bitly` | Required if scheduled sync enabled, apply enabled, and `require-bitly-when-apply-enabled=true` | Bitly base URL, access token, and group GUID. |

Startup logs:

| Event | Meaning |
| --- | --- |
| `image_hosting.readiness.startup` | Overall readiness result at startup. |
| `image_hosting.readiness.check` | One component check with status and requirement flag. |

## Metrics

Prometheus uses Micrometer naming conventions. For example, counter `image_hosting_sync` appears as `image_hosting_sync_total` in Prometheus.

### Image Hosting Metrics

| Meter | Type | Tags | Emitted by |
| --- | --- | --- | --- |
| `image_hosting_sync` | Counter | `provider`, `outcome`, `dry_run` | Manual direct sync endpoint. |
| `image_hosting_sync_duration` | Timer | `provider`, `outcome`, `dry_run` | Manual direct sync endpoint. |
| `image_hosting_photo_upload` | Counter | `provider`, `result` | Manual direct sync photo operations. |
| `image_hosting_album_operation` | Counter | `provider`, `operation`, `result` | Album create/membership/metadata operations. |
| `image_hosting_album_creation` | Counter | `provider`, `result` | Sync-plan album creation. |
| `image_hosting_album_adoption` | Counter | `provider`, `result` | Album adoption/repair planning. |
| `image_hosting_short_url` | Counter | `provider`, `operation`, `result` | Bitly short URL operations. |
| `image_hosting_scheduled_sync` | Counter | `provider`, `outcome` | Scheduled image-hosting sync runs. |
| `image_hosting_scheduled_sync_duration` | Timer | `provider`, `outcome` | Scheduled image-hosting sync runs. |
| `image_hosting_scheduled_sync_inventory` | Counter | `provider`, `result` | Per-inventory scheduled sync results. |

### BrickLink Order Sync Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `bricklink_order_sync` | Counter | `provider`, `outcome` | One count per BrickLink order sync run. |
| `bricklink_order_sync_duration` | Timer | `provider`, `outcome` | Run duration. |
| `bricklink_order_sync_order` | Counter | `provider`, `result` | Order counts by `discovered`, `fetched`, `failed`, `written`. |

### Fulfillment Sync Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `fulfillment_sync` | Counter | `provider`, `outcome` | One count per fulfillment sync run. |
| `fulfillment_sync_duration` | Timer | `provider`, `outcome` | Fulfillment sync run duration. |
| `fulfillment_sync_order` | Counter | `provider`, `result` | Order counts by `discovered`, `loaded`, `payload_missing`, `mapped`, `created`, `updated`, `shipped_reconciled`, `skipped`, and `failed`. |

### Photo Processing Metrics

| Meter | Type | Tags | Description |
| --- | --- | --- | --- |
| `photo.processed` | Counter | `mode` | Successful photo processing count. `mode` is usually `event` or `batch`. |
| `photo.failed` | Counter | `mode` | Failed photo processing count. |
| `photo.duplicate` | Counter | `mode` | Duplicate photo count. |
| `photo.skipped` | Counter | `mode`, `reason` | Skipped photo count. |
| `photo.processing.time` | Timer | `mode` | Photo processing duration. |

## Structured Logs

### BrickLink REST HTTP Logs

When `bricklink.rest.http-logging.enabled=true` and logger `com.bricklink.api` is DEBUG, the REST client logs:

| Event | Fields |
| --- | --- |
| `bricklink.rest.request` | HTTP method, URI, headers when enabled, body when enabled. |
| `bricklink.rest.response` | HTTP method, URI, status code/text, headers when enabled, body when enabled. |

Authorization and cookies are redacted by the logging interceptor.

### BrickLink Order Sync Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `bricklink.order_sync.probe.started` | Run start with direction/statuses/apply. |
| `bricklink.order_sync.probe.no_work` | No orders discovered. |
| `bricklink.order_sync.probe.order_fetched` | One order fetched with important summary fields. |
| `bricklink.order_sync.probe.coverage` | Order-level field coverage. |
| `bricklink.order_sync.probe.item_coverage` | Item-level field coverage counts. |
| `bricklink.order_sync.probe.completed` | Final outcome and counters. |
| `bricklink.order_sync.probe.failed` | Run-level failure. |

### Fulfillment Sync Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `fulfillment.sync_job.started` | Run start with provider, marketplace, statuses, batch size, and apply mode. |
| `fulfillment.sync_job.no_work` | No local fulfillment candidates were selected. |
| `fulfillment.sync_job.payload_missing` | Candidate lacks the latest staged `ORDER_RESPONSE` or `ORDER_ITEMS_RESPONSE` payload. |
| `fulfillment.order_item_image.fallback` | Item had no primary external image URL, so a non-primary available image URL was used. |
| `fulfillment.sync_job.order_mapped` | Staged BrickLink order mapped to a ShipStation order and records action `SKIPPED_DRY_RUN`, `CREATED`, `UPDATED`, or `SHIPPED_RECONCILED`. |
| `fulfillment.sync_job.shipstation_synced` | ShipStation create/update completed in apply mode. |
| `fulfillment.sync_job.bricklink_shipped_reconciled` | Tracking/status were pushed back to BrickLink for an already-shipped ShipStation order. |
| `fulfillment.sync_job.order_failed` | One candidate failed; the job continues. |
| `fulfillment.sync_job.completed` | Run-level outcome and counters. |

### Image Hosting Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `image_hosting.sync.started` | Manual direct sync started. |
| `image_hosting.sync.completed` | Manual direct sync completed. |
| `image_hosting.sync.failed` | Manual direct sync failed. |
| `image_hosting.photo_upload.started` | Photo upload started. |
| `image_hosting.photo_upload.completed` | Photo upload completed. |
| `image_hosting.photo_upload.skipped` | Photo upload skipped. |
| `image_hosting.photo_upload.failed` | Photo upload failed. |
| `image_hosting.photo_metadata_update.started` | Photo metadata update started. |
| `image_hosting.photo_metadata_update.completed` | Photo metadata update completed. |
| `image_hosting.photo_metadata_update.failed` | Photo metadata update failed. |
| `image_hosting.album.create.started` | Album create started. |
| `image_hosting.album.create.completed` | Album create completed. |
| `image_hosting.album.create.failed` | Album create failed. |
| `image_hosting.album_membership.update.started` | Album membership update started. |
| `image_hosting.album_membership.update.completed` | Album membership update completed. |
| `image_hosting.album_membership.update.failed` | Album membership update failed. |
| `image_hosting.sync_job.*` | Scheduled job lifecycle and per-inventory result events. |
| `image_hosting.readiness.*` | Startup readiness evaluation. |

### Photo Logs

Primary events:

| Event | Purpose |
| --- | --- |
| `photo.kafka.consume.start` | Photo upload event consumption started. |
| `photo.kafka.consume.success` | Photo upload event processed successfully. |
| `photo.kafka.consume.complete` | Photo upload event consumption completed with duration. |
| `photo.process.start` | Photo processing started. |
| `photo.process.success` | Photo processing succeeded. |
| `photo.process.source_missing` | Source object missing for stale S3 event. |
| `photo.process.rejected` | Photo rejected by validation/business rules. |
| `photo.process.failed` | Photo processing failed. |
| `photo.process.complete` | Photo processing completed with duration. |
| `photo.delete.kafka.consume.start` | Photo delete event consumption started. |
| `photo.delete.kafka.consume.success` | Photo delete event processed successfully. |
| `photo.delete.kafka.consume.complete` | Photo delete event consumption completed with duration. |
| `photo.delete.process.start` | Delete service started processing an object delete. |

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

### Safe Fulfillment Sync Rollout

Prerequisite: run the BrickLink order sync with `apply=true` first so `marketplace_order`, `marketplace_order_item`, and `marketplace_order_payload` contain current staged order data.

1. Confirm BrickLink API credentials are present and `bricklink.rest.uri=https://api.bricklink.com/api/store/v1`.
2. Confirm ShipStation credentials are supplied through `shipstation.rest.api-key` and `shipstation.rest.api-secret`.
3. Confirm the candidate statuses in `lego.fulfillment.statuses` match the local marketplace order statuses intended for fulfillment.
4. Keep `lego.fulfillment.sync.scheduled.enabled=true`.
5. Keep `lego.fulfillment.sync.scheduled.apply=false` for initial validation.
6. Watch `fulfillment.sync_job.order_mapped` and confirm generated order numbers, item counts, statuses, image fallback logs, insurance options, and international behavior look correct.
7. Confirm `payloadsMissing=0` or understand why specific orders are missing staged raw payloads.
8. Set `lego.fulfillment.sync.scheduled.apply=true` only after dry-run mapping is verified.
9. After enabling apply, verify ShipStation orders are created or updated with `BL-{orderId}` order numbers.
10. For already-shipped ShipStation orders, verify BrickLink receives tracking data, status `SHIPPED`, and drive-thru is sent only when needed.

Rollback:

```yaml
lego:
  fulfillment:
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

select marketplace_code, external_order_id, external_status_code, order_direction, ordered_at, grand_total_amount, last_seen_at
from marketplace_order
where marketplace_code = 'BRICKLINK'
order by last_seen_at desc
limit 25;

select oi.*
from marketplace_order_item oi
join marketplace_order o on o.marketplace_order_id = oi.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
order by oi.marketplace_order_id desc, oi.marketplace_order_item_id;

select o.marketplace_code, o.external_order_id, p.payload_type_code, p.payload_hash, p.captured_at
from marketplace_order_payload p
join marketplace_order o
  on o.marketplace_order_id = p.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
order by p.marketplace_order_payload_id desc
limit 25;
```

### Fulfillment Sync SQL Checks

Fulfillment does not currently write new local fulfillment tables. It reads staged marketplace order data and writes to ShipStation and, for shipped reconciliation, BrickLink. These checks validate whether local staged data is ready for fulfillment:

```sql
select marketplace_order_id, marketplace_code, external_order_id, external_status_code, last_seen_at
from marketplace_order
where marketplace_code = 'BRICKLINK'
  and external_status_code in ('PENDING', 'UPDATED', 'READY', 'PROCESSING', 'PAID', 'PACKED')
order by last_seen_at desc
limit 25;

select o.external_order_id,
       count(*) as item_count,
       sum(case when oi.marketplace_listing_id is null then 1 else 0 end) as missing_listing_link_count,
       sum(case when oi.item_inventory_id is null then 1 else 0 end) as missing_inventory_link_count
from marketplace_order o
join marketplace_order_item oi
  on oi.marketplace_order_id = o.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
group by o.external_order_id
order by o.external_order_id desc;

select o.external_order_id,
       p.payload_type_code,
       p.payload_hash,
       p.captured_at
from marketplace_order o
join marketplace_order_payload p
  on p.marketplace_order_id = o.marketplace_order_id
where o.marketplace_code = 'BRICKLINK'
  and p.payload_type_code in ('ORDER_RESPONSE', 'ORDER_ITEMS_RESPONSE')
order by p.marketplace_order_payload_id desc
limit 50;

select oi.external_order_item_id,
       oi.item_inventory_id,
       iip.item_inventory_photo_id,
       iip.primary,
       ei.image_url
from marketplace_order_item oi
left join item_inventory_photo iip
  on iip.item_inventory_id = oi.item_inventory_id
left join external_image ei
  on ei.item_inventory_photo_id = iip.item_inventory_photo_id
 and ei.external_service_id = 10
where oi.marketplace_order_id = 100
order by oi.marketplace_order_item_id, iip.primary desc, iip.item_inventory_photo_id;
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

| Symptom | Likely cause | Check/fix |
| --- | --- | --- |
| Application fails because config import is missing | Required external file absent under `import-path` | Create/project the expected YAML file or adjust active profiles. |
| BrickLink responses are `302` to error page | Wrong BrickLink base URI | Use `https://api.bricklink.com/api/store/v1`. |
| BrickLink sync discovers orders but writes nothing | `lego.bricklink.orders.sync.scheduled.apply=false` | This is probe mode. Set apply true only after DB tables exist and probe output is reviewed. |
| Fulfillment sync reports `payloadsMissing` | BrickLink order staging has not written both raw payload rows for candidate orders | Run BrickLink order sync with `apply=true` and confirm `ORDER_RESPONSE` and `ORDER_ITEMS_RESPONSE` payloads exist. |
| Fulfillment dry-run maps orders but ShipStation does not change | `lego.fulfillment.sync.scheduled.apply=false` | This is expected dry-run behavior. |
| Fulfillment apply fails with missing ShipStation client | `shipstation.rest.api-key` or `shipstation.rest.api-secret` is absent | Supply ShipStation credentials through external config or environment. |
| Fulfillment shipped reconciliation fails with missing BrickLink client | BrickLink REST credentials are absent or BrickLink REST config is not active | Supply `bricklink.rest.*` credentials and base URI before apply-mode shipped reconciliation. |
| Fulfillment candidate fails because multiple ShipStation orders match | More than one exact `BL-{orderId}` order exists in ShipStation | Resolve duplicate ShipStation orders manually before re-running apply mode. |
| Fulfillment shipped reconciliation fails because no shipment has tracking | ShipStation order is shipped but shipments are voided or lack tracking numbers | Correct shipment/tracking data in ShipStation, then re-run. |
| Image-hosting scheduled job logs planned actions but no writes | `lego.image-hosting.sync.scheduled.apply=false` | This is dry-run scheduled mode. |
| Kubernetes readiness DOWN for image hosting | Missing required DB/S3/Flickr/Bitly config or invalid scheduled settings | Inspect `/actuator/health/readiness` details and startup readiness logs. |
| Kafka listener not consuming | Topic id missing consumer config or wrong group/topic | Check `kafka.topic-configuration.<id>.*` and dynamic bean registration logs. |
| Kafka JSON deserialization failure | Missing/wrong `spring-json-value-default-type` or trusted packages | Check per-topic consumer properties and `kafka.consumer-defaults.properties.spring.json.trusted.packages`. |
| Photo delete throughput setting ignored in YAML | Listener concurrency is hard-coded to `6` | Code change required to make it configurable. |

## Safety Rules

- Keep scheduled `apply=false` during first deployments or after large refactors.
- Do not enable BrickLink HTTP body logging in long-running production-like deployments unless needed for diagnosis.
- Do not add high-cardinality metric tags such as order ids, photo ids, album ids, UUIDs, file names, or exception messages.
- Treat all `/internal/*` endpoints as administrative operations. They should remain behind trusted network/access controls.
- Prefer sync-plan and repair-plan endpoints over the legacy direct sync endpoint for manual image-hosting operations.
- Before enabling write-side marketplace sync, confirm the marketplace sync tables and constraints exist in the target database.
- Before enabling fulfillment apply mode, confirm BrickLink order staging is current and inspect fulfillment dry-run logs for the same status set.
- Do not enable fulfillment apply mode with production ShipStation credentials unless duplicate `BL-{orderId}` orders have been ruled out.
