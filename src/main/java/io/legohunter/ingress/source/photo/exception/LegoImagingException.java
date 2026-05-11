package io.legohunter.ingress.source.photo.exception;

public class LegoImagingException extends RuntimeException {
    public LegoImagingException() {
    }

    public LegoImagingException(String message) {
        super(message);
    }

    public LegoImagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public LegoImagingException(Throwable cause) {
        super(cause);
    }

    public LegoImagingException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
