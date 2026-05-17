package io.legohunter.ingress.util.exception;

@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}