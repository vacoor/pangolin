package com.github.pangolin.routing.rule;

import com.github.pangolin.routing.rule.pattern.DestinationPattern;

import java.util.Map;

public interface RulesProvider {

    Map<DestinationPattern, String> getRules();

}