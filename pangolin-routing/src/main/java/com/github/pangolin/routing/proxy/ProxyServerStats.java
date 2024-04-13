package com.github.pangolin.routing.proxy;

public class ProxyServerStats {
    private final String name;
    private final AvgMinMaxCounter responseTimeCounter = new AvgMinMaxCounter("ResponseTime");

    public ProxyServerStats(final String name) {
        this.name = name;
    }

    public void addResponseTime(final long msecs) {
        responseTimeCounter.add(msecs);
    }

    public double getResponseTimeAvg() {
        return responseTimeCounter.getAvg();
    }

}