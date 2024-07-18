package com.github.pangolin.routing.v2.server;

import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;

public interface MixinServerHandshakerFactory {

    MixinServerHandshaker createHandshaker();

}
