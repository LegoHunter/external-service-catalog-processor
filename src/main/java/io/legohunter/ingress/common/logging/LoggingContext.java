package io.legohunter.ingress.common.logging;

import org.slf4j.MDC;

import java.util.UUID;

public class LoggingContext {

    public static final String CORRELATION_ID = "correlationId";

    public static void init() {
        MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
    }

    public static void clear() {
        MDC.clear();
    }
}