package com.github.pangolin.routing.upstream.spi;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpstreamUrlTestFactory extends UpstreamAdaptiveFactory {

    @Override
    public String name() {
        return "url-test";
    }
}
