package io.legohunter.ingress.source.bricklink.pricing;

record BricklinkListingCreateSafetyResult(
        boolean allowed,
        String statusCode,
        String message,
        String desiredRemarks,
        String desiredRemarksHash,
        boolean stockRoom,
        String stockRoomId,
        boolean publiclyAvailable
) {
    static BricklinkListingCreateSafetyResult allowed(
            String desiredRemarks,
            boolean stockRoom,
            String stockRoomId,
            boolean publiclyAvailable
    ) {
        return new BricklinkListingCreateSafetyResult(
                true,
                "VERIFIED",
                "Local listing passed BrickLink create safety checks",
                desiredRemarks,
                BricklinkInventorySystemRemarks.sha256(desiredRemarks),
                stockRoom,
                stockRoomId,
                publiclyAvailable
        );
    }

    static BricklinkListingCreateSafetyResult blocked(String statusCode, String message) {
        return new BricklinkListingCreateSafetyResult(false, statusCode, message, null, null, false, null, false);
    }
}
