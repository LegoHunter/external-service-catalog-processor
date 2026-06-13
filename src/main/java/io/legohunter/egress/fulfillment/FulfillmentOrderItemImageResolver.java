package io.legohunter.egress.fulfillment;

import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dao.MarketplaceOrderItemDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class FulfillmentOrderItemImageResolver {
    private final MarketplaceOrderItemDao marketplaceOrderItemDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalImageDao externalImageDao;
    private final FulfillmentSyncProperties properties;

    public Map<String, String> resolveImageUrls(MarketplaceOrder marketplaceOrder) {
        if (marketplaceOrder == null || marketplaceOrder.getMarketplaceOrderId() == null) {
            return Map.of();
        }

        Integer externalServiceId = properties.getShipstation().effectiveOrderItemImageExternalServiceId();
        Map<String, String> imageUrlsByExternalOrderItemId = new LinkedHashMap<>();
        for (MarketplaceOrderItem orderItem : marketplaceOrderItemDao.findByMarketplaceOrderId(marketplaceOrder.getMarketplaceOrderId())) {
            if (orderItem.getExternalOrderItemId() == null || orderItem.getItemInventoryId() == null) {
                continue;
            }
            Optional<String> imageUrl = resolveImageUrl(orderItem.getItemInventoryId(), externalServiceId);
            imageUrl.ifPresent(url -> imageUrlsByExternalOrderItemId.put(orderItem.getExternalOrderItemId(), url));
        }
        return imageUrlsByExternalOrderItemId;
    }

    private Optional<String> resolveImageUrl(Integer itemInventoryId, Integer externalServiceId) {
        Set<ItemInventoryPhoto> photos = itemInventoryPhotoDao.findByItemInventoryId(itemInventoryId);
        Optional<AvailablePhoto> primaryPhoto = photos.stream()
                .filter(photo -> Boolean.TRUE.equals(photo.getPrimary()))
                .map(photo -> availablePhoto(photo, externalServiceId))
                .flatMap(Optional::stream)
                .findFirst();
        if (primaryPhoto.isPresent()) {
            return Optional.of(primaryPhoto.get().imageUrl());
        }

        Optional<AvailablePhoto> fallbackPhoto = photos.stream()
                .map(photo -> availablePhoto(photo, externalServiceId))
                .flatMap(Optional::stream)
                .findFirst();
        fallbackPhoto.ifPresent(photo -> log.info(
                "fulfillment.order_item_image.fallback itemInventoryId={} itemInventoryPhotoId={} externalServiceId={}",
                itemInventoryId,
                photo.itemInventoryPhotoId(),
                externalServiceId
        ));
        return fallbackPhoto.map(AvailablePhoto::imageUrl);
    }

    private Optional<AvailablePhoto> availablePhoto(ItemInventoryPhoto photo, Integer externalServiceId) {
        if (photo == null || photo.getItemInventoryPhotoId() == null) {
            return Optional.empty();
        }
        return externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(
                        externalServiceId,
                        photo.getItemInventoryPhotoId()
                )
                .map(ExternalImage::getImageUrl)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(imageUrl -> new AvailablePhoto(photo.getItemInventoryPhotoId(), imageUrl));
    }

    private record AvailablePhoto(Integer itemInventoryPhotoId, String imageUrl) {
    }
}
