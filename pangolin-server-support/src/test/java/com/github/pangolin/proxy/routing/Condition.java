package com.github.pangolin.proxy.routing;

/**
 */
public interface Condition<T> {

    boolean matches(final T t);

}
