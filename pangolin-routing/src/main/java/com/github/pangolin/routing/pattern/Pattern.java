package com.github.pangolin.routing.pattern;

/**
 */
public interface Pattern<T> {

    boolean matches(final T t);

}
