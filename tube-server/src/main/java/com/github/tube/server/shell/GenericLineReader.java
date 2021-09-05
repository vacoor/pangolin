package com.github.tube.server.shell;

import java.io.*;

public class GenericLineReader implements LineReader {
    private final BufferedReader reader;
    private final Writer writer;

    public GenericLineReader(final Reader reader, final Writer writer) {
        this.reader = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        this.writer = writer;
    }

    @Override
    public String readLine() throws IOException {
        writer.write("tunnel# ");
        writer.flush();
        return reader.readLine();
    }
}