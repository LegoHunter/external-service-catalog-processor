# Lego Data Ingress

## Flickr Integration Phase 1

Phase 1 exposes a temporary internal endpoint for manually syncing one item inventory record at a time to the configured image hosting provider.

The endpoint defaults to dry-run mode and does not make provider calls or write `external_image` tables unless `dryRun=false` is supplied.

```http
POST /internal/image-hosting/item-inventories/{itemInventoryId}/sync
```

Dry run:

```http
POST /internal/image-hosting/item-inventories/100/sync
```

Real sync:

```http
POST /internal/image-hosting/item-inventories/100/sync?dryRun=false
```

Optional provider override:

```http
POST /internal/image-hosting/item-inventories/100/sync?dryRun=false&externalServiceId=10
```

Flickr is disabled by default. Enable it only in a local or environment-specific configuration that has access to the Flickr client configuration consumed by `lego-imaging`.

```yaml
lego:
  image-hosting:
    flickr:
      enabled: true
    sync:
      external-service-id: 10

flickr:
  application-name: lego-data-ingress
  client-config-dir: /path/to/secure/config
  client-config-file: flickr-client.json
```

Phase 1 deliberately stays single-item only. Batch scheduling, delete sync, replacement sync, retries, and real Flickr smoke tests are deferred until the manual path is stable.

The current upload path writes MinIO objects to a temporary local file before calling `lego-imaging` because the provider facade accepts `PhotoMetaDataV1` file paths today. Keep this adapter isolated in the sync service until `lego-imaging` supports stream or byte-array uploads.
