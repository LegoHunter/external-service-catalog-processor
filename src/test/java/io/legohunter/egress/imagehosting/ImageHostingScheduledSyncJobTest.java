package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ExternalImageDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.FAILED;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageHostingScheduledSyncJobTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @Mock
    private ExternalImageDao externalImageDao;

    @Mock
    private ImageHostingSyncService imageHostingSyncService;

    private ImageHostingSyncProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private ImageHostingScheduledSyncJob job;

    @BeforeEach
    void setUp() {
        properties = new ImageHostingSyncProperties();
        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setEnabled(true);
        flickr.setExternalServiceId(FLICKR_SERVICE_ID);
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);
        properties.getSync().getScheduled().setBatchSize(3);
        properties.getSync().getScheduled().setConcurrency(2);

        meterRegistry = new SimpleMeterRegistry();
        job = new ImageHostingScheduledSyncJob(
                externalImageDao,
                imageHostingSyncService,
                properties,
                new ImageHostingSyncMetricsService(meterRegistry)
        );
    }

    @Test
    void runOnceRecordsNoWorkWhenDiscoveryReturnsNoInventories() {
        when(externalImageDao.findItemInventoryIdsNeedingSync(FLICKR_SERVICE_ID, false, 3))
                .thenReturn(List.of());

        ImageHostingScheduledSyncResult result = job.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.itemInventoriesDiscovered()).isZero();
        assertThat(result.itemInventoryIds()).isEmpty();
        verify(imageHostingSyncService, never()).sync(any());
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync",
                "provider", "flickr",
                "outcome", "no_work"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void runOnceSyncsDiscoveredInventoriesWithConfiguredConcurrency() throws Exception {
        properties.getSync().getScheduled().setRetryFailed(true);
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        when(externalImageDao.findItemInventoryIdsNeedingSync(FLICKR_SERVICE_ID, true, 3))
                .thenReturn(List.of(101, 102, 103));
        when(imageHostingSyncService.sync(any())).thenAnswer(invocation -> {
            ImageHostingSyncRequest request = invocation.getArgument(0);
            int currentActive = active.incrementAndGet();
            maxActive.accumulateAndGet(currentActive, Math::max);
            firstTwoStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            active.decrementAndGet();
            return ImageHostingSyncResult.builder()
                    .itemInventoryId(request.getItemInventoryId())
                    .provider(request.getProvider())
                    .externalServiceId(request.getExternalServiceId())
                    .retryFailed(request.isRetryFailed())
                    .outcome(SUCCESS)
                    .build();
        });

        CompletableFuture<ImageHostingScheduledSyncResult> future = CompletableFuture.supplyAsync(job::runOnce);
        assertThat(firstTwoStarted.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        ImageHostingScheduledSyncResult result = future.get(2, TimeUnit.SECONDS);

        assertThat(maxActive.get()).isEqualTo(2);
        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.itemInventoryIds()).containsExactly(101, 102, 103);
        assertThat(result.syncedItemInventoryIds()).containsExactly(101, 102, 103);
        assertThat(result.failedItemInventoryIds()).isEmpty();
        ArgumentCaptor<ImageHostingSyncRequest> requestCaptor = ArgumentCaptor.forClass(ImageHostingSyncRequest.class);
        verify(imageHostingSyncService, org.mockito.Mockito.times(3)).sync(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(ImageHostingSyncRequest::getItemInventoryId)
                .containsExactlyInAnyOrder(101, 102, 103);
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.getProvider()).isEqualTo("flickr");
                    assertThat(request.getExternalServiceId()).isEqualTo(FLICKR_SERVICE_ID);
                    assertThat(request.isRetryFailed()).isTrue();
                    assertThat(request.isDryRun()).isFalse();
                });
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync_inventory",
                "provider", "flickr",
                "result", "synced"
        ).count()).isEqualTo(3.0);
    }

    @Test
    void runOnceContinuesWhenAnInventoryFails() {
        properties.getSync().getScheduled().setConcurrency(1);
        when(externalImageDao.findItemInventoryIdsNeedingSync(FLICKR_SERVICE_ID, false, 3))
                .thenReturn(List.of(201, 202, 203));
        when(imageHostingSyncService.sync(any())).thenAnswer(invocation -> {
            ImageHostingSyncRequest request = invocation.getArgument(0);
            if (request.getItemInventoryId() == 202) {
                return ImageHostingSyncResult.builder()
                        .itemInventoryId(202)
                        .outcome(FAILED)
                        .build();
            }
            if (request.getItemInventoryId() == 203) {
                throw new IllegalStateException("provider unavailable");
            }
            return ImageHostingSyncResult.builder()
                    .itemInventoryId(201)
                    .outcome(SUCCESS)
                    .build();
        });

        ImageHostingScheduledSyncResult result = job.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.syncedItemInventoryIds()).containsExactly(201);
        assertThat(result.failedItemInventoryIds()).containsExactly(202, 203);
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync",
                "provider", "flickr",
                "outcome", "partial_failure"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "image_hosting_scheduled_sync_inventory",
                "provider", "flickr",
                "result", "failed"
        ).count()).isEqualTo(2.0);
    }
}
