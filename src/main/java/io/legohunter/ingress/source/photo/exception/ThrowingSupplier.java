package io.legohunter.ingress.source.photo.exception;

@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}