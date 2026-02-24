package io.legohunter.ingress.source.bricklink.catalog.service;

import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import lombok.RequiredArgsConstructor;
import net.lego.data.v2.dao.ExternalItemDao;
import net.lego.data.v2.dto.ExternalItem;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogEntryProjectionService {

    private final ExternalItemDao externalItemDao;

    private static final int BRICKLINK_SERVICE_ID = 2;

    public void upsert(CatalogEntry entry) {
        ExternalItem externalItem = new ExternalItem();
        externalItem.setNumber(entry.getItemId());
        externalItem.setUniqueId(0L);
        externalItem.setName(entry.getItemName());
        externalItem.setItemType(entry.getItemType());
        externalItem.setUrl(null);
        externalItem.setCategoryId(entry.getCategory());
        externalItem.setYearReleased(entry.getItemYear());
        externalItem.setServiceId(BRICKLINK_SERVICE_ID);

        externalItemDao.upsert(externalItem);
    }
}