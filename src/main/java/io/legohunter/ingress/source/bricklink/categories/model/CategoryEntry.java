package io.legohunter.ingress.source.bricklink.categories.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Setter
@Getter
@ToString
@Slf4j
public class CategoryEntry {
    @JacksonXmlProperty(localName = "CATEGORY")
    private Integer category;

    @JacksonXmlProperty(localName = "CATEGORYNAME")
    private String categoryName;
}
