package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.model.v1.Item;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dao.PricingSnapshotDao;
import io.legohunter.data.dao.PricingSnapshotListingDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingCrawlWorkItem;
import io.legohunter.data.dto.PricingSnapshot;
import io.legohunter.data.dto.PricingSnapshotListing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingCrawlServiceTest {
    private BricklinkAjaxClient bricklinkAjaxClient;
    private MarketplaceListingDao marketplaceListingDao;
    private ExternalCatalogItemDao externalCatalogItemDao;
    private ItemInventoryDao itemInventoryDao;
    private PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private PricingSnapshotDao pricingSnapshotDao;
    private PricingSnapshotListingDao pricingSnapshotListingDao;
    private BricklinkPricingCrawlService service;

    @BeforeEach
    void setUp() {
        bricklinkAjaxClient = mock(BricklinkAjaxClient.class);
        marketplaceListingDao = mock(MarketplaceListingDao.class);
        externalCatalogItemDao = mock(ExternalCatalogItemDao.class);
        itemInventoryDao = mock(ItemInventoryDao.class);
        pricingCrawlWorkItemDao = mock(PricingCrawlWorkItemDao.class);
        pricingSnapshotDao = mock(PricingSnapshotDao.class);
        pricingSnapshotListingDao = mock(PricingSnapshotListingDao.class);

        BricklinkPricingCrawlProperties properties = new BricklinkPricingCrawlProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setResultsPerPage(500);

        when(pricingCrawlWorkItemDao.insert(any())).thenAnswer(invocation -> {
            PricingCrawlWorkItem workItem = invocation.getArgument(0);
            workItem.setPricingCrawlWorkItemId(100L);
            return workItem;
        });
        when(pricingCrawlWorkItemDao.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pricingSnapshotDao.insert(any())).thenAnswer(invocation -> {
            PricingSnapshot snapshot = invocation.getArgument(0);
            snapshot.setPricingSnapshotId(200L);
            return snapshot;
        });
        when(pricingSnapshotListingDao.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(externalCatalogItemDao.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new BricklinkPricingCrawlService(
                bricklinkAjaxClient,
                properties,
                marketplaceListingDao,
                externalCatalogItemDao,
                itemInventoryDao,
                pricingCrawlWorkItemDao,
                pricingSnapshotDao,
                pricingSnapshotListingDao,
                new ObjectMapper()
        );
    }

    @Test
    void runOnceHydratesMissingInternalItemIdAndPersistsSnapshotListings() {
        ExternalCatalogItem catalogItem = catalogItem(null);
        MarketplaceListing listing = listing(catalogItem);
        when(marketplaceListingDao.findByListingExternalServiceIdAndListingStatusCode(2, "ACTIVE", 10))
                .thenReturn(Set.of(listing));
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.of(searchItem(4997)));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenReturn(catalogResult(itemForSale(3001, "seller1", "US $220.00")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.listingsSelected()).isOne();
        assertThat(result.hydratedCatalogItems()).isOne();
        assertThat(result.snapshotsWritten()).isOne();
        assertThat(result.snapshotListingsWritten()).isOne();
        assertThat(catalogItem.getExternalUniqueKey()).isEqualTo("4997");
        verify(externalCatalogItemDao).update(catalogItem);
        verify(bricklinkAjaxClient).catalogItemsForSaleByInternalItemId(4997, "U", 500);
        verify(pricingSnapshotDao).insert(any(PricingSnapshot.class));
        verify(pricingSnapshotListingDao).insert(any(PricingSnapshotListing.class));
    }

    @Test
    void runOnceSkipsCatalogSearchWhenInternalItemIdAlreadyExists() {
        ExternalCatalogItem catalogItem = catalogItem("4997");
        MarketplaceListing listing = listing(catalogItem);
        when(marketplaceListingDao.findByListingExternalServiceIdAndListingStatusCode(2, "ACTIVE", 10))
                .thenReturn(Set.of(listing));
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(4997, "U", 500))
                .thenReturn(catalogResult(itemForSale(3001, "seller1", "US $220.00")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.hydratedCatalogItems()).isZero();
        assertThat(result.snapshotsWritten()).isOne();
        verify(bricklinkAjaxClient, never()).findCatalogItem(any(), any());
        verify(externalCatalogItemDao, never()).update(any());
    }

    @Test
    void runOnceRecordsNoMatchLookupFailureWithoutCallingPricingEndpoint() {
        ExternalCatalogItem catalogItem = catalogItem(null);
        MarketplaceListing listing = listing(catalogItem);
        when(marketplaceListingDao.findByListingExternalServiceIdAndListingStatusCode(2, "ACTIVE", 10))
                .thenReturn(Set.of(listing));
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory("USED", "COMPLETE")));
        when(bricklinkAjaxClient.findCatalogItem("6390-1", "S")).thenReturn(Optional.empty());

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.failedListings()).isOne();
        verify(bricklinkAjaxClient, never()).catalogItemsForSaleByInternalItemId(any(), any(), any());
        verify(pricingCrawlWorkItemDao).update(any(PricingCrawlWorkItem.class));
        verify(pricingSnapshotDao, never()).insert(any());
    }

    @Test
    void runOnceSkipsMissingInventoryConditionBeforeAjaxCalls() {
        ExternalCatalogItem catalogItem = catalogItem(null);
        MarketplaceListing listing = listing(catalogItem);
        when(marketplaceListingDao.findByListingExternalServiceIdAndListingStatusCode(2, "ACTIVE", 10))
                .thenReturn(Set.of(listing));
        when(itemInventoryDao.findByItemInventoryId(20)).thenReturn(Optional.of(inventory(null, "COMPLETE")));

        BricklinkPricingCrawlResult result = service.runOnce();

        assertThat(result.skippedListings()).isOne();
        verify(bricklinkAjaxClient, never()).findCatalogItem(any(), any());
        verify(bricklinkAjaxClient, never()).catalogItemsForSaleByInternalItemId(any(), any(), any());
    }

    private MarketplaceListing listing(ExternalCatalogItem catalogItem) {
        return MarketplaceListing.builder()
                .marketplaceListingId(10)
                .itemInventoryId(20)
                .listingExternalServiceId(2)
                .externalCatalogItemId(30)
                .externalListingId("BL-INV-10")
                .listingStatusCode("ACTIVE")
                .unitPrice(new BigDecimal("250.00"))
                .currencyCode("USD")
                .externalCatalogItem(catalogItem)
                .build();
    }

    private ExternalCatalogItem catalogItem(String externalUniqueKey) {
        return ExternalCatalogItem.builder()
                .externalCatalogItemId(30)
                .externalServiceId(2)
                .externalItemKey("6390-1")
                .externalUniqueKey(externalUniqueKey)
                .itemName("Main Street")
                .itemTypeCode("SET")
                .build();
    }

    private ItemInventory inventory(String newOrUsed, String completeness) {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(20);
        inventory.setNewOrUsed(newOrUsed);
        inventory.setCompleteness(completeness);
        return inventory;
    }

    private Item searchItem(Integer idItem) {
        Item item = new Item();
        item.setIdItem(idItem);
        item.setTypeItem("S");
        item.setStrItemNo("6390-1");
        item.setStrItemName("Main Street");
        return item;
    }

    private CatalogItemsForSaleResult catalogResult(ItemForSale... listings) {
        CatalogItemsForSaleResult result = new CatalogItemsForSaleResult();
        result.setTotal_count(listings.length);
        result.setRpp(500);
        result.setPi(1);
        result.setList(List.of(listings));
        return result;
    }

    private ItemForSale itemForSale(Integer idInv, String sellerName, String displayPrice) {
        ItemForSale itemForSale = new ItemForSale();
        itemForSale.setIdInv(idInv);
        itemForSale.setStrSellerUsername(sellerName);
        itemForSale.setStrSellerCountryCode("US");
        itemForSale.setCodeNew("U");
        itemForSale.setCodeComplete("C");
        itemForSale.setN4Qty(1);
        itemForSale.setMDisplaySalePrice(displayPrice);
        itemForSale.setStrDesc("Complete used set");
        return itemForSale;
    }
}
