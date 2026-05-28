package io.legohunter.egress.imagehosting.description;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@Builder
public class GeneratedItemDescription {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
    private String title;
    private String description;
    private String photoUrl;

    @Singular
    private List<String> facts;

    @Singular
    private List<String> captions;

    public List<String> getFacts() {
        return Optional.ofNullable(facts).orElse(Collections.emptyList());
    }

    public List<String> getCaptions() {
        return Optional.ofNullable(captions).orElse(Collections.emptyList());
    }
}
