package com.github.pangolin.server.shell;

import java.io.IOException;

public interface LineReader {

    String readLine() throws IOException;

    void close() throws IOException;

}
