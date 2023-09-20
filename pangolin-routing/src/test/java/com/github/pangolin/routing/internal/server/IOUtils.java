package com.github.pangolin.routing.internal.server;

import com.github.pangolin.routing.internal.server.trojan.TrojanServerResolver;
import freework.codec.Base64;
import freework.net.Http;
import io.netty.util.internal.ObjectUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;

public class IOUtils {

    public static List<String> read(final InputStream in) throws IOException {
        return read(new InputStreamReader(in));
    }

    public static List<String> read(final Reader reader) throws IOException {
        ObjectUtil.checkNotNull(reader, "reader");
        final List<String> lines = new LinkedList<String>();
        final BufferedReader r = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while (null != (line = r.readLine())) {
//            final int index = line.indexOf('#');
//            final String lineToUse = -1 < index ? line.substring(0, index).trim() : line.trim();
            final String lineToUse = line;
            if (!lineToUse.isEmpty()) {
                lines.add(lineToUse);
            }
        }
        return lines;
    }


}
