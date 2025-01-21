package com.github.pangolin.tun.tun2socks;

import freework.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractTun2Socks {
    protected static final Pattern TUN2SOCKS_READY_PATTERN = Pattern.compile(".*tun://(.*) <-> .*");

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Process tun2SocksProcess;
    private volatile String device;

    public void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("This tun2socks instance is already running...");
        }

        tun2SocksProcess = createProcessBuilder().start();
        device = awaitTun2SocksReady(tun2SocksProcess, tun2socksReadyPattern());
        onReady();
    }

    public String getDevice() {
        return device;
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

    private String awaitTun2SocksReady(final Process process, final Pattern readyRegexPattern) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        try {
            String outputLine;
            String deviceName;
            do {
                outputLine = reader.readLine();
                if (outputLine == null) {
                    //Something goes wrong. Stream is ended before server was activated.
                    throw new RuntimeException("Can't start tun2socks. Check logs for details.");
                }
                System.out.println(outputLine);
            } while (null == (deviceName = matches(readyRegexPattern, outputLine)));
            return deviceName;
        } finally {
            IOUtils.close(reader);
        }
    }

    private String matches(final Pattern pattern, final String line) {
        final Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    protected Pattern tun2socksReadyPattern() {
        return TUN2SOCKS_READY_PATTERN;
    }

    protected abstract ProcessBuilder createProcessBuilder() throws Exception;

    protected void onReady() throws Exception {}

}