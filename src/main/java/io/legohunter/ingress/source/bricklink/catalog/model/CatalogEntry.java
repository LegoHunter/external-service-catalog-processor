package io.legohunter.ingress.source.bricklink.catalog.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Setter
@Getter
@ToString
@Slf4j
public class CatalogEntry {

    @JacksonXmlProperty(localName = "ITEMTYPE")
    private String itemType;

    @JacksonXmlProperty(localName = "ITEMID")
    private String itemId;

    @JacksonXmlProperty(localName = "ITEMNAME")
    private String itemName;

    @JacksonXmlProperty(localName = "CATEGORY")
    private Integer category;

    @JacksonXmlProperty(localName = "ITEMYEAR")
    private Integer itemYear;

    @JacksonXmlProperty(localName = "ITEMWEIGHT")
    private String itemWeight;

    @JacksonXmlProperty(localName = "ITEMDIMX")
    private Float itemDimX;

    @JacksonXmlProperty(localName = "ITEMDIMY")
    private Float itemDimY;

    @JacksonXmlProperty(localName = "ITEMDIMZ")
    private Float itemDimZ;
}
