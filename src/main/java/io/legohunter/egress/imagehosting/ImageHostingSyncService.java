package io.legohunter.egress.imagehosting;

public interface ImageHostingSyncService {
    ImageHostingSyncResult syncItemInventory(Integer itemInventoryId);

    ImageHostingSyncResult sync(ImageHostingSyncRequest request);
}
