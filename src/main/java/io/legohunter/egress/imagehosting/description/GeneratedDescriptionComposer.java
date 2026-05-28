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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.legohunter.data.dto.ExternalService.ExternalServiceType.BRICKLINK;

@Service
@RequiredArgsConstructor
public class GeneratedDescriptionComposer {
    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalItemDao externalItemDao;
    private final ExternalItemInventoryDao externalItemInventoryDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ImageHostingSyncProperties properties;

    public GeneratedItemDescription compose(GeneratedDescriptionRequest request) {
        if (request == null || request.getItemInventoryId() == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }

        ImageHostingSyncProperties.ResolvedProvider provider = properties.resolveProvider(
                request.getProvider(),
                request.getExternalServiceId()
        );
        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(request.getItemInventoryId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No item inventory found for id [%s]".formatted(request.getItemInventoryId())
                ));
        ExternalItem externalItem = bricklinkExternalItem(inventory).orElse(null);
        ExternalImageAlbum album = externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(
                provider.externalServiceId(),
                inventory.getItemInventoryId()
        ).orElse(null);
        List<ItemInventoryPhoto> photos = sortedPhotos(itemInventoryPhotoDao.findByItemInventoryId(
                inventory.getItemInventoryId()
        ));

        return compose(provider, inventory, externalItem, album, photos);
    }

    public GeneratedItemDescription compose(
            ImageHostingSyncProperties.ResolvedProvider provider,
            ItemInventory inventory,
            ExternalItem externalItem,
            ExternalImageAlbum album,
            List<ItemInventoryPhoto> photos
    ) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        if (inventory == null) {
            throw new IllegalArgumentException("inventory is required");
        }

        String title = title(inventory, externalItem);
        List<String> facts = facts(inventory);
        List<String> captions = captions(photos);
        String photoUrl = photoUrl(album);
        String description = description(inventory, title, facts, captions, photoUrl);

        GeneratedItemDescription.GeneratedItemDescriptionBuilder builder = GeneratedItemDescription.builder()
                .itemInventoryId(inventory.getItemInventoryId())
                .provider(provider.provider())
                .externalServiceId(provider.externalServiceId())
                .title(title)
                .description(description)
                .photoUrl(photoUrl);
        facts.forEach(builder::fact);
        captions.forEach(builder::caption);
        return builder.build();
    }

    private Optional<ExternalItem> bricklinkExternalItem(ItemInventory inventory) {
        return externalItemInventoryDao.findByItemInventoryId(inventory.getItemInventoryId()).stream()
                .map(ExternalItemInventory::getExternalItemId)
                .map(externalItemDao::findByExternalItemId)
                .flatMap(Optional::stream)
                .filter(item -> BRICKLINK.getExternalServiceId().equals(item.getServiceId()))
                .filter(item -> hasText(item.getExternalNumber()))
                .filter(item -> hasText(item.getName()))
                .findFirst();
    }

    private String title(ItemInventory inventory, ExternalItem externalItem) {
        if (externalItem != null) {
            return "%s - %s".formatted(externalItem.getExternalNumber(), externalItem.getName());
        }

        if (hasText(inventory.getDescription())) {
            return inventory.getDescription().trim();
        }

        return "Inventory %s".formatted(inventory.getUuid());
    }

    private List<String> facts(ItemInventory inventory) {
        List<String> facts = new ArrayList<>();
        addTextFact(facts, "New or used", inventory.getNewOrUsed());
        addTextFact(facts, "Completeness", inventory.getCompleteness());
        addConditionFact(facts, "Item condition", inventory.getItemConditionId());
        addConditionFact(facts, "Box condition", inventory.getBoxConditionId());
        addConditionFact(facts, "Instructions condition", inventory.getInstructionsConditionId());
        addBooleanFact(facts, "Sealed", inventory.getSealed());
        addBooleanFact(facts, "Built once", inventory.getBuiltOnce());
        return facts;
    }

    private void addTextFact(List<String> facts, String label, String value) {
        if (hasText(value)) {
            facts.add("%s: %s".formatted(label, value.trim()));
        }
    }

    private void addConditionFact(List<String> facts, String label, Integer conditionId) {
        conditionLabel(conditionId).ifPresent(value -> facts.add("%s: %s".formatted(label, value)));
    }

    private void addBooleanFact(List<String> facts, String label, Boolean value) {
        if (value != null) {
            facts.add("%s: %s".formatted(label, value ? "yes" : "no"));
        }
    }

    private Optional<String> conditionLabel(Integer conditionId) {
        return ConditionEnum.fromId(conditionId)
                .map(this::conditionLabel);
    }

    private String conditionLabel(ConditionEnum condition) {
        return switch (condition) {
            case M -> "Mint";
            case E -> "Excellent";
            case VG -> "Very good";
            case G -> "Good";
            case F -> "Fair";
            case P -> "Poor";
            case NA -> "Not applicable";
            case MS -> "Missing";
            case CC -> "Color copy";
            case BW -> "Black and white copy";
            case SL -> "Sealed";
        };
    }

    private List<String> captions(List<ItemInventoryPhoto> photos) {
        return Optional.ofNullable(photos).orElse(List.of()).stream()
                .map(ItemInventoryPhoto::getCaption)
                .filter(this::hasText)
                .map(String::trim)
                .toList();
    }

    private String photoUrl(ExternalImageAlbum album) {
        if (album == null) {
            return null;
        }
        if (hasText(album.getShortUrl())) {
            return album.getShortUrl().trim();
        }
        if (hasText(album.getAlbumUrl())) {
            return album.getAlbumUrl().trim();
        }
        return null;
    }

    private String description(
            ItemInventory inventory,
            String title,
            List<String> facts,
            List<String> captions,
            String photoUrl
    ) {
        List<String> paragraphs = new ArrayList<>();
        if (hasText(title)) {
            paragraphs.add("%s.".formatted(stripTrailingPeriod(title.trim())));
        }
        if (!facts.isEmpty()) {
            paragraphs.add(String.join(". ", facts) + ".");
        }
        if (!captions.isEmpty()) {
            paragraphs.add(String.join(" ", captions));
        }
        if (hasText(photoUrl)) {
            paragraphs.add("Photos: %s".formatted(photoUrl));
        }

        if (paragraphs.isEmpty()) {
            return "Inventory item [%s]".formatted(inventory.getUuid());
        }

        return String.join("\n\n", paragraphs);
    }

    private String stripTrailingPeriod(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private List<ItemInventoryPhoto> sortedPhotos(Set<ItemInventoryPhoto> photos) {
        return Optional.ofNullable(photos).orElse(Set.of()).stream()
                .sorted(Comparator.comparing(
                        ItemInventoryPhoto::getItemInventoryPhotoId,
                        Comparator.nullsLast(Integer::compareTo)
                ))
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
