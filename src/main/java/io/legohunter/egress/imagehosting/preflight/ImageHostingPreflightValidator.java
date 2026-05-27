package io.legohunter.egress.imagehosting.preflight;

import io.legohunter.egress.imagehosting.ImageHostingSyncRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;

public interface ImageHostingPreflightValidator {
    ImageHostingPreflightResult validate(ImageHostingDesiredStateSnapshot snapshot, ImageHostingSyncRequest request);
}
