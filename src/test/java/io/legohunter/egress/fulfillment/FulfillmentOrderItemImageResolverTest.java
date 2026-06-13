package io.legohunter.egress.fulfillment;

import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceOrderItemDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FulfillmentOrderItemImageResolverTest {
    private MarketplaceOrderItemDao marketplaceOrderItemDao;
    private ItemInventoryPhotoDao itemInventoryPhotoDao;
    private ExternalImageDao externalImageDao;
    private FulfillmentOrderItemImageResolver resolver;

    @BeforeEach
    void setUp() {
        marketplaceOrderItemDao = mock(MarketplaceOrderItemDao.class);
        itemInventoryPhotoDao = mock(ItemInventoryPhotoDao.class);
        externalImageDao = mock(ExternalImageDao.class);
        resolver = new FulfillmentOrderItemImageResolver(
                marketplaceOrderItemDao,
                itemInventoryPhotoDao,
                externalImageDao,
                new FulfillmentSyncProperties()
        );
    }

    @Test
    void resolveImageUrlsUsesPrimaryPhotoWhenHostedImageExists() {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20);
        MarketplaceOrderItem orderItem = marketplaceOrderItem("100:inventory:3001", 501);
        ItemInventoryPhoto primaryPhoto = photo(11, 501, true);
        ItemInventoryPhoto otherPhoto = photo(12, 501, false);
        when(marketplaceOrderItemDao.findByMarketplaceOrderId(20)).thenReturn(Set.of(orderItem));
        when(itemInventoryPhotoDao.findByItemInventoryId(501)).thenReturn(Set.of(otherPhoto, primaryPhoto));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(10, 11))
                .thenReturn(Optional.of(externalImage("https://photos.example/primary.jpg")));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(10, 12))
                .thenReturn(Optional.of(externalImage("https://photos.example/other.jpg")));

        Map<String, String> result = resolver.resolveImageUrls(marketplaceOrder);

        assertThat(result).containsExactly(Map.entry("100:inventory:3001", "https://photos.example/primary.jpg"));
    }

    @Test
    void resolveImageUrlsFallsBackToAnyHostedPhotoWhenPrimaryIsUnavailable() {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20);
        MarketplaceOrderItem orderItem = marketplaceOrderItem("100:inventory:3001", 501);
        ItemInventoryPhoto primaryPhoto = photo(11, 501, true);
        ItemInventoryPhoto fallbackPhoto = photo(12, 501, false);
        when(marketplaceOrderItemDao.findByMarketplaceOrderId(20)).thenReturn(Set.of(orderItem));
        when(itemInventoryPhotoDao.findByItemInventoryId(501)).thenReturn(Set.of(primaryPhoto, fallbackPhoto));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(10, 11))
                .thenReturn(Optional.empty());
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(10, 12))
                .thenReturn(Optional.of(externalImage("https://photos.example/fallback.jpg")));

        Map<String, String> result = resolver.resolveImageUrls(marketplaceOrder);

        assertThat(result).containsExactly(Map.entry("100:inventory:3001", "https://photos.example/fallback.jpg"));
    }

    @Test
    void resolveImageUrlsLeavesOrderItemUnmappedWhenNoHostedPhotosExist() {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20);
        MarketplaceOrderItem orderItem = marketplaceOrderItem("100:inventory:3001", 501);
        ItemInventoryPhoto photo = photo(11, 501, true);
        when(marketplaceOrderItemDao.findByMarketplaceOrderId(20)).thenReturn(Set.of(orderItem));
        when(itemInventoryPhotoDao.findByItemInventoryId(501)).thenReturn(Set.of(photo));
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(10, 11))
                .thenReturn(Optional.of(externalImage(" ")));

        Map<String, String> result = resolver.resolveImageUrls(marketplaceOrder);

        assertThat(result).isEmpty();
    }

    private static MarketplaceOrder marketplaceOrder(Integer marketplaceOrderId) {
        return MarketplaceOrder.builder()
                .marketplaceOrderId(marketplaceOrderId)
                .externalOrderId("100")
                .build();
    }

    private static MarketplaceOrderItem marketplaceOrderItem(String externalOrderItemId, Integer itemInventoryId) {
        return MarketplaceOrderItem.builder()
                .externalOrderItemId(externalOrderItemId)
                .itemInventoryId(itemInventoryId)
                .build();
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, Integer itemInventoryId, boolean primary) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(itemInventoryId)
                .primary(primary)
                .build();
    }

    private static ExternalImage externalImage(String imageUrl) {
        return ExternalImage.builder()
                .imageUrl(imageUrl)
                .build();
    }
}
