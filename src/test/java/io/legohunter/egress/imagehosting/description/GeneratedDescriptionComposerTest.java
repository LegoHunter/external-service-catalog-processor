package io.legohunter.egress.imagehosting.description;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalItemDao;
import io.legohunter.data.dao.ExternalItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalItem;
import io.legohunter.data.dto.ExternalItemInventory;
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
    private ExternalItemDao externalItemDao;
    private ExternalItemInventoryDao externalItemInventoryDao;
    private ExternalImageAlbumDao externalImageAlbumDao;
    private GeneratedDescriptionComposer composer;

    @BeforeEach
    void setUp() {
        itemInventoryDao = mock(ItemInventoryDao.class);
        itemInventoryPhotoDao = mock(ItemInventoryPhotoDao.class);
        externalItemDao = mock(ExternalItemDao.class);
        externalItemInventoryDao = mock(ExternalItemInventoryDao.class);
        externalImageAlbumDao = mock(ExternalImageAlbumDao.class);
        composer = new GeneratedDescriptionComposer(
                itemInventoryDao,
                itemInventoryPhotoDao,
                externalItemDao,
                externalItemInventoryDao,
                externalImageAlbumDao,
                properties()
        );
    }

    @Test
    void composeBuildsDescriptionFromDurableDbState() {
        ItemInventory inventory = inventory();
        ExternalItem externalItem = externalItem();
        ExternalImageAlbum album = ExternalImageAlbum.builder()
                .shortUrl("https://flic.kr/s/a100")
                .albumUrl("https://www.flickr.com/photos/example/albums/100")
                .build();

        GeneratedItemDescription description = composer.compose(
                new ImageHostingSyncProperties.ResolvedProvider("flickr", "Flickr", "flickr", FLICKR_SERVICE_ID),
                inventory,
                externalItem,
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
    void composeRequestLoadsInventoryExternalItemAlbumAndSortedPhotos() {
        ItemInventory inventory = inventory();
        ExternalItem externalItem = externalItem();
        ExternalImageAlbum album = ExternalImageAlbum.builder()
                .albumUrl("https://www.flickr.com/photos/example/albums/100")
                .build();

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(externalItemInventoryDao.findByItemInventoryId(100)).thenReturn(List.of(ExternalItemInventory.builder()
                .externalItemId(501)
                .itemInventoryId(100)
                .build()));
        when(externalItemDao.findByExternalItemId(501)).thenReturn(Optional.of(externalItem));
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

    private static ExternalItem externalItem() {
        ExternalItem externalItem = new ExternalItem();
        externalItem.setExternalItemId(501);
        externalItem.setServiceId(2);
        externalItem.setExternalNumber("4558-1");
        externalItem.setName("Metroliner");
        return externalItem;
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, String caption) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .caption(caption)
                .build();
    }
}
