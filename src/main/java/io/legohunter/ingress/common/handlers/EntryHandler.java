package io.legohunter.ingress.common.handlers;

public interface EntryHandler<T> {
    void handle(T t);
    Class<T> getType();
}
