package io.legohunter.egress.imagehosting.remote;

public interface ImageHostingRemoteSnapshotReader {
    ImageHostingRemoteSnapshot read(ImageHostingRemoteSnapshotRequest request);
}
