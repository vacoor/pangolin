package com.github.tube.server.shell;

import java.io.*;

public class GenericLineReader implements LineReader {
    private final BufferedReader reader;
    private final Writer writer;

    public GenericLineReader(final InputStream in, final OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in));
        this.writer = new OutputStreamWriter(out);
    }

    @Override
    public String readLine() throws IOException {
        writer.write("tunnel# ");
        writer.flush();
        return reader.readLine();
    }
}