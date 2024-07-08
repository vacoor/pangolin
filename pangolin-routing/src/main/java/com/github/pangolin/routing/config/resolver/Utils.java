package com.github.pangolin.routing.config.resolver;

import com.google.common.io.Resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.stream.Stream;

/**
 *
 */
public class Utils {

    public static Stream<String> lines(final URL url, final Charset charset) throws IOException {
        final BufferedReader reader = Resources.asCharSource(url, charset)
                .openBufferedStream();
        return reader.lines()
                .map(line -> {
                    final int index = line.indexOf('#');
                    return -1 < index ? line.substring(0, index).trim() : line.trim();
                })
                .onClose(() -> {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    public static boolean isDigit(final String text) {
        if (null != text && 0 < text.length()) {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

}
