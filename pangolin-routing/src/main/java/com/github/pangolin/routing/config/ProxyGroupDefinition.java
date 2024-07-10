package com.github.pangolin.routing.config;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 *
 */
@Getter
@Setter
public class ProxyGroupDefinition {
    private String name;
    private String type;
    private String url;
    private List<String> proxies;
}
