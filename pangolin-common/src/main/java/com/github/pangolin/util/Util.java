package com.github.pangolin.util;

import java.util.List;
import java.util.Map;

public class Util {

    private Util() {
    }

    public static String last(final Map<String, List<String>> params, final String key) {
        final List<String> values = null != params ? params.get(key) : null;
        return null != values && !values.isEmpty() ? values.get(values.size() - 1) : null;
    }

}