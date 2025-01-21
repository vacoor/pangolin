package com.github.pangolin.routing.server.fakedns;

import com.github.pangolin.routing.context.RouteContext;

public interface ApplicationInitializer {

    void onStartup(final RouteContext context);

}