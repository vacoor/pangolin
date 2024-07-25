package com.github.pangolin.routing.route.predicate;

/**
 */
public class UnknownRoutePredicate<T> implements RoutePredicate<T> {
    private final String unknown;

    public UnknownRoutePredicate(final String unknown) {
        this.unknown = unknown;
    }

    @Override
    public boolean test(final T t) {
        return false;
    }

    @Override
    public String toString() {
        return "UNKNOWN/" + unknown;
    }

    public static <T> UnknownRoutePredicate<T> of(final String unknown) {
        return new UnknownRoutePredicate<>(unknown);
    }
}
