package io.legohunter.egress.imagehosting.description;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventoryExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.metadata.model.ConditionEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedDescriptionComposerTest {
    private static final int FLICKR_SERVICE_ID = 10;

    private ItemInventoryDao itemInventoryDao;
    private ItemInventoryPhotoDao itemInventoryPhotoDao;
    private ExternalCatalogItemDao externalCatalogItemDao;
    private ItemInventoryExternalCatalogItemDao itemInventoryExternalCatalogItemDao;
    private ExternalImageAlbumDao externalImageAlbumDao;
    private GeneratedDescriptionComposer composer;

    @BeforeEach
    void setUp() {
        itemInventoryDao = mock(ItemInventoryDao.class);
        itemInventoryPhotoDao = mock(ItemInventoryPhotoDao.class);
        externalCatalogItemDao = mock(ExternalCatalogItemDao.class);
        itemInventoryExternalCatalogItemDao = mock(ItemInventoryExternalCatalogItemDao.class);
        externalImageAlbumDao = mock(ExternalImageAlbumDao.class);
        composer = new GeneratedDescriptionComposer(
                itemInventoryDao,
                itemInventoryPhotoDao,
                externalCatalogItemDao,
                itemInventoryExternalCatalogItemDao,
                externalImageAlbumDao,
                properties()
        );
    }

    @Test
    void composeBuildsDescriptionFromDurableDbState() {
        ItemInventory inventory = inventory();
        ExternalCatalogItem externalCatalogItem = externalCatalogItem();
        ExternalImageAlbum album = ExternalImageAlbum.builder()
                .shortUrl("https://flic.kr/s/a100")
                .albumUrl("https://www.flickr.com/photos/example/albums/100")
                .build();

        GeneratedItemDescription description = composer.compose(
                new ImageHostingSyncProperties.ResolvedProvider("flickr", "Flickr", "flickr", FLICKR_SERVICE_ID),
                inventory,
                externalCatalogItem,
                album,
                List.of(photo(12, "Back caption"), photo(11, "Front caption"))
        );

        assertThat(description)
                .extracting(
                        GeneratedItemDescription::getItemInventoryId,
                        GeneratedItemDescription::getProvider,
                        GeneratedItemDescription::getExternalServiceId,
                        GeneratedItemDescription::getTitle,
                        GeneratedItemDescription::getPhotoUrl
                )
                .containsExactly(100, "flickr", FLICKR_SERVICE_ID, "4558-1 - Metroliner", "https://flic.kr/s/a100");
        assertThat(description.getFacts()).containsExactly(
                "New or used: Used",
                "Completeness: Complete",
                "Item condition: Excellent",
                "Box condition: Very good",
                "Instructions condition: Missing",
                "Sealed: no",
                "Built once: yes"
        );
        assertThat(description.getCaptions()).containsExactly("Back caption", "Front caption");
        assertThat(description.getDescription()).isEqualTo("""
                4558-1 - Metroliner.

                New or used: Used. Completeness: Complete. Item condition: Excellent. Box condition: Very good. Instructions condition: Missing. Sealed: no. Built once: yes.

                Back caption Front caption

                Photos: https://flic.kr/s/a100""");
    }

    @Test
    void composeRequestLoadsInventoryExternalCatalogItemAlbumAndSortedPhotos() {
        ItemInventory inventory = inventory();
        ExternalCatalogItem externalCatalogItem = externalCatalogItem();
        ExternalImageAlbum album = ExternalImageAlbum.builder()
                .albumUrl("https://www.flickr.com/photos/example/albums/100")
                .build();

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryExternalCatalogItemDao.findByItemInventoryId(100)).thenReturn(Set.of(ItemInventoryExternalCatalogItem.builder()
                .externalCatalogItemId(501)
                .itemInventoryId(100)
                .build()));
        when(externalCatalogItemDao.findByExternalCatalogItemId(501)).thenReturn(Optional.of(externalCatalogItem));
        when(externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(FLICKR_SERVICE_ID, 100))
                .thenReturn(Optional.of(album));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(
                photo(12, "Back caption"),
                photo(11, "Front caption")
        ));

        GeneratedItemDescription description = composer.compose(GeneratedDescriptionRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(description.getPhotoUrl()).isEqualTo("https://www.flickr.com/photos/example/albums/100");
        assertThat(description.getCaptions()).containsExactly("Front caption", "Back caption");
    }

    @Test
    void composeFallsBackToInventoryDescriptionAndLegacyUuidWhenNoDurableFieldsExist() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        inventory.setDescription("Inventory album.");

        GeneratedItemDescription description = composer.compose(
                new ImageHostingSyncProperties.ResolvedProvider("flickr", "Flickr", "flickr", FLICKR_SERVICE_ID),
                inventory,
                null,
                null,
                List.of(photo(11, " "))
        );

        assertThat(description.getTitle()).isEqualTo("Inventory album.");
        assertThat(description.getDescription()).isEqualTo("Inventory album.");

        inventory.setDescription(null);
        GeneratedItemDescription fallback = composer.compose(
                new ImageHostingSyncProperties.ResolvedProvider("flickr", "Flickr", "flickr", FLICKR_SERVICE_ID),
                inventory,
                null,
                null,
                List.of()
        );
        assertThat(fallback.getDescription()).isEqualTo("Inventory inventory-uuid.");
    }

    @Test
    void composeRejectsMissingRequestDataAndUnknownInventory() {
        assertThatThrownBy(() -> composer.compose((GeneratedDescriptionRequest) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemInventoryId is required");
        assertThatThrownBy(() -> composer.compose(GeneratedDescriptionRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemInventoryId is required");

        when(itemInventoryDao.findByItemInventoryId(404)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> composer.compose(GeneratedDescriptionRequest.builder()
                .itemInventoryId(404)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No item inventory found for id [404]");
    }

    private static ImageHostingSyncProperties properties() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.setDefaultProvider("flickr");
        properties.getSync().setExternalServiceId(FLICKR_SERVICE_ID);

        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setExternalServiceId(FLICKR_SERVICE_ID);
        flickr.setDisplayName("Flickr");
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);

        return properties;
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        inventory.setDescription("Inventory album");
        inventory.setNewOrUsed("Used");
        inventory.setCompleteness("Complete");
        inventory.setItemConditionId(ConditionEnum.E.conditionId());
        inventory.setBoxConditionId(ConditionEnum.VG.conditionId());
        inventory.setInstructionsConditionId(ConditionEnum.MS.conditionId());
        inventory.setSealed(false);
        inventory.setBuiltOnce(true);
        return inventory;
    }

    private static ExternalCatalogItem externalCatalogItem() {
        ExternalCatalogItem externalCatalogItem = new ExternalCatalogItem();
        externalCatalogItem.setExternalCatalogItemId(501);
        externalCatalogItem.setExternalServiceId(2);
        externalCatalogItem.setExternalItemKey("4558-1");
        externalCatalogItem.setItemName("Metroliner");
        return externalCatalogItem;
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, String caption) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .caption(caption)
                .build();
    }
}
