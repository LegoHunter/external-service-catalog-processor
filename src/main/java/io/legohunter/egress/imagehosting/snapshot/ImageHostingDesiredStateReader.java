package io.legohunter.egress.imagehosting.snapshot;

public interface ImageHostingDesiredStateReader {
    ImageHostingDesiredStateSnapshot read(ImageHostingDesiredStateRequest request);
}
