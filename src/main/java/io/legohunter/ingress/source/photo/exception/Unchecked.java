package io.legohunter.ingress.source.photo.exception;

import java.util.function.Function;
import java.util.function.Supplier;

public final class Unchecked {

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

    // keep simple version too
    public static <T> Supplier<T> wrap(ThrowingSupplier<T> supplier) {
        return wrap(supplier, RuntimeException::new);
    }
}
