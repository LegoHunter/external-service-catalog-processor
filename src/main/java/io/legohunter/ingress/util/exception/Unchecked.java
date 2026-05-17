package io.legohunter.ingress.util.exception;

import java.util.function.Function;
import java.util.function.Supplier;

public final class Unchecked {

    private Unchecked() {
    }

    public static <T> Supplier<T> wrap(
            ThrowingSupplier<T> supplier,
            Function<Exception, RuntimeException> exceptionMapper
    ) {
        return () -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                throw exceptionMapper.apply(e);
            }
        };
    }
}
