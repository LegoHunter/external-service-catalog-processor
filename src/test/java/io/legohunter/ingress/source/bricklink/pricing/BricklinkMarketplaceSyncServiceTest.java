package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkMarketplaceSyncServiceTest {
    private final BricklinkMarketplaceSyncProperties properties = new BricklinkMarketplaceSyncProperties();
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final ItemInventoryDao itemInventoryDao = mock(ItemInventoryDao.class);
    private final BricklinkRestClient bricklinkRestClient = mock(BricklinkRestClient.class);
    private final BricklinkMarketplaceSyncService service = new BricklinkMarketplaceSyncService(
            properties,
            marketplaceListingSyncRequestDao,
            marketplaceListingDao,
            bricklinkMarketplaceListingDao,
            itemInventoryDao,
            bricklinkRestClient,
            new BricklinkRemoteInventorySafetyService()
    );

    @Test
    void dryRunVerifiesRemoteInventoryWithoutClaimingOrUpdating() {
        properties.setMode("DRY_RUN");
        arrangeRequestAndContext(remoteInventory(systemBlock()));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.DRY_RUN);
        assertThat(result.dryRunVerified()).isOne();
        verify(marketplaceListingSyncRequestDao, never()).claim(any(), any(), any(), any());
        verify(bricklinkRestClient, never()).updateInventory(any(), any());
        verify(marketplaceListingSyncRequestDao, never()).update(any());
    }

    @Test
    void applyUpdatesRemoteInventoryAndCompletesRequestWhenSafetyPasses() {
        properties.setMode("APPLY");
        MarketplaceListingSyncRequest request = request();
        arrangeRequestAndContext(remoteInventory("Human notes " + systemBlock()));
        when(marketplaceListingSyncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedRequest()));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bricklinkMarketplaceListingDao.update(any(BricklinkMarketplaceListing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkMarketplaceSyncMode.APPLY);
        assertThat(result.requestsClaimed()).isOne();
        assertThat(result.remoteUpdated()).isOne();
        verify(bricklinkRestClient).updateInventory(eq(12345L), any(Inventory.class));
        verify(marketplaceListingSyncRequestDao).update(any(MarketplaceListingSyncRequest.class));
        verify(bricklinkMarketplaceListingDao).update(any(BricklinkMarketplaceListing.class));
        assertThat(request.getSyncRequestStatusCode()).isEqualTo("PENDING");
    }

    @Test
    void applyBlocksRequestWhenSystemBlockIsMissing() {
        properties.setMode("APPLY");
        arrangeRequestAndContext(remoteInventory("Human notes only"));
        when(marketplaceListingSyncRequestDao.claim(eq(900L), eq("PENDING"), eq("CLAIMED"), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(claimedRequest()));
        when(marketplaceListingSyncRequestDao.update(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkMarketplaceSyncResult result = service.runOnce();

        assertThat(result.blocked()).isOne();
        verify(bricklinkRestClient, never()).updateInventory(any(), any());
        verify(marketplaceListingSyncRequestDao).update(any(MarketplaceListingSyncRequest.class));
    }

    private void arrangeRequestAndContext(Inventory remoteInventory) {
        when(marketplaceListingSyncRequestDao.findClaimableByStatusCode(eq("PENDING"), any(ZonedDateTime.class), eq(5)))
                .thenReturn(Set.of(request()));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing()));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        when(itemInventoryDao.findByItemInventoryId(200)).thenReturn(Optional.of(itemInventory()));
        when(bricklinkRestClient.getInventories(12345L)).thenReturn(resource(remoteInventory));
    }

    private MarketplaceListingSyncRequest request() {
        return MarketplaceListingSyncRequest.builder()
                .marketplaceListingSyncRequestId(900L)
                .marketplaceListingId(100)
                .listingExternalServiceId(2)
                .pricingDecisionId(500L)
                .syncRequestTypeCode("PRICE_UPDATE")
                .syncRequestStatusCode("PENDING")
                .requestedUnitPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .remoteInventoryId("12345")
                .attemptCount(0)
                .maxAttempts(3)
                .build();
    }

    private MarketplaceListingSyncRequest claimedRequest() {
        MarketplaceListingSyncRequest request = request();
        request.setSyncRequestStatusCode("CLAIMED");
        request.setAttemptCount(1);
        return request;
    }

    private MarketplaceListing listing() {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .build();
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .bricklinkInventoryId(12345)
                .build();
    }

    private ItemInventory itemInventory() {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setItemInventoryId(200);
        itemInventory.setUuid("inventory-uuid");
        return itemInventory;
    }

    private Inventory remoteInventory(String remarks) {
        Inventory inventory = new Inventory();
        inventory.setInventory_id(12345L);
        inventory.setIs_stock_room(true);
        inventory.setStock_room_id("A");
        inventory.setRemarks(remarks);
        return inventory;
    }

    private BricklinkResource<Inventory> resource(Inventory inventory) {
        BricklinkResource<Inventory> resource = new BricklinkResource<>();
        resource.setData(inventory);
        return resource;
    }

    private String systemBlock() {
        return "[SYSTEM_BEGIN] LEGOHUNTER_MANAGED=true; LEGOHUNTER_ENV=local; MARKETPLACE_LISTING_ID=100; ITEM_INVENTORY_UUID=inventory-uuid [SYSTEM_END]";
    }
}
