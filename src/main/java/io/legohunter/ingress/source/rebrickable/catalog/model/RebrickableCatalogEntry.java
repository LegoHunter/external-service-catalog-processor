package io.legohunter.ingress.source.rebrickable.catalog.model;

import lombok.Data;

@Data
public class RebrickableCatalogEntry {
    private String setNum;
    private String name;
    private Integer year;
    private Integer themeId;
    private Integer numParts;
    private String imgUrl;
}