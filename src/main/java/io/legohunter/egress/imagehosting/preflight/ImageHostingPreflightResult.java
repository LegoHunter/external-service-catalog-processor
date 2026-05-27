package io.legohunter.egress.imagehosting.preflight;

import lombok.Builder;
import lombok.Singular;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Builder
public class ImageHostingPreflightResult {
    @Singular
    private List<ImageHostingPreflightIssue> issues;

    public static ImageHostingPreflightResult valid() {
        return ImageHostingPreflightResult.builder().build();
    }

    public static ImageHostingPreflightResult withIssues(List<ImageHostingPreflightIssue> issues) {
        return ImageHostingPreflightResult.builder()
                .issues(issues)
                .build();
    }

    public List<ImageHostingPreflightIssue> issues() {
        return Optional.ofNullable(issues).orElse(Collections.emptyList());
    }

    public boolean isValid() {
        return issues().isEmpty();
    }
}
