package com.github.pangolin.routing.extra.fakedns;

import com.github.pangolin.routing.context.RouteContext;

public interface ApplicationInitializer {

    void onStartup(final RouteContext context);

}