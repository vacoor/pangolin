package com.github.pangolin.server.shell;

import jline.TerminalSupport;

public class WebSocketTerminal extends TerminalSupport {
    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;

    private int cols = DEFAULT_COLS;
    private int rows = DEFAULT_ROWS;

    public WebSocketTerminal() {
        super(true);
        this.init();
    }

    @Override
    public void init() {
        setEchoEnabled(false);
        setAnsiSupported(true);
    }

    @Override
    public int getWidth() {
        return cols;
    }

    @Override
    public int getHeight() {
        return rows;
    }

    public void setCols(final int cols) {
        this.cols = cols;
    }

    public void setRows(final int rows) {
        this.rows = rows;
    }

    @Override
    public String getOutputEncoding() {
        return "UTF-8";
    }

    @Override
    public boolean hasWeirdWrap() {
        return true;
    }
}