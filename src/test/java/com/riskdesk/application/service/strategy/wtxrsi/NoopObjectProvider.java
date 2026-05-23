package com.riskdesk.application.service.strategy.wtxrsi;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Test-only no-op {@link ObjectProvider}. Returns null for every lookup —
 * lets test doubles call {@code super(...)} without bringing Mockito into scope.
 */
final class NoopObjectProvider<T> implements ObjectProvider<T> {

    @Override public T getObject() { return null; }
    @Override public T getObject(Object... args) { return null; }
    @Override public T getIfAvailable() { return null; }
    @Override public T getIfUnique() { return null; }
    @Override public T getIfAvailable(java.util.function.Supplier<T> defaultSupplier) {
        return defaultSupplier.get();
    }
    @Override public T getIfUnique(java.util.function.Supplier<T> defaultSupplier) {
        return defaultSupplier.get();
    }
    @Override public void ifAvailable(java.util.function.Consumer<T> dependencyConsumer)
            throws BeansException { /* no-op */ }
    @Override public void ifUnique(java.util.function.Consumer<T> dependencyConsumer)
            throws BeansException { /* no-op */ }
}
