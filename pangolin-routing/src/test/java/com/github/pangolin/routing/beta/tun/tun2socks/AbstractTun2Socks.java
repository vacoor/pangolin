package com.github.pangolin.routing.beta.tun.tun2socks;

import freework.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractTun2Socks {
    protected static final String TUN2SOCKS_READY_PATTERN = ".*tun://.* <-> .*";

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Process tun2SocksProcess;

    public void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("This tun2socks instance is already running...");
        }

        tun2SocksProcess = createProcessBuilder().start();

        awaitTun2SocksReady(tun2SocksProcess, tun2socksReadyPattern());
        onReady();
    }

    public void stop() {
        if (null != tun2SocksProcess) {
            tun2SocksProcess.destroy();
            tryWaitFor(tun2SocksProcess);
        }
    }

    private void tryWaitFor(final Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to stop redis instance", e);
        }
    }

    private void awaitTun2SocksReady(final Process process, final String readyRegexPattern) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        try {
            String outputLine;
            do {
                outputLine = reader.readLine();
                if (outputLine == null) {
                    //Something goes wrong. Stream is ended before server was activated.
                    throw new RuntimeException("Can't start tun2socks. Check logs for details.");
                }
                System.out.println(outputLine);
            } while (!outputLine.matches(readyRegexPattern));
        } finally {
            IOUtils.close(reader);
        }
    }

    protected String tun2socksReadyPattern() {
        return TUN2SOCKS_READY_PATTERN;
    }

    protected abstract ProcessBuilder createProcessBuilder() throws Exception;

    protected void onReady() throws Exception {}

}