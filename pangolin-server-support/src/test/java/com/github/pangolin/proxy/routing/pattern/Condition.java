package com.github.pangolin.proxy.routing.pattern;

/**
 */
public interface Condition<T> {

    boolean matches(final T t);

}
