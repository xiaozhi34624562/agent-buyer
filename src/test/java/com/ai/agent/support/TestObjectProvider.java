package com.ai.agent.support;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class TestObjectProvider<T> implements ObjectProvider<T> {
    private final T value;

    private TestObjectProvider(T value) {
        this.value = value;
    }

    public static <T> TestObjectProvider<T> of(T value) {
        return new TestObjectProvider<>(value);
    }

    public static <T> TestObjectProvider<T> empty() {
        return new TestObjectProvider<>(null);
    }

    @Override
    public T getObject(Object... args) {
        return getObject();
    }

    @Override
    public T getIfAvailable() {
        return value;
    }

    @Override
    public T getIfUnique() {
        return value;
    }

    @Override
    public T getObject() {
        if (value == null) {
            throw new NoSuchBeanDefinitionException("test object provider has no value");
        }
        return value;
    }

    @Override
    public Iterator<T> iterator() {
        if (value == null) {
            return Collections.emptyIterator();
        }
        return List.of(value).iterator();
    }
}
