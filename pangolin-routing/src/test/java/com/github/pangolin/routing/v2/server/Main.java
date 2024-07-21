package com.github.pangolin.routing.v2.server;

import com.github.pangolin.routing.handler.mixin.MixinServerHandshaker;

import java.util.ServiceLoader;

public class Main {

    public static void main(String[] args) {
        final ServiceLoader<MixinServerHandshakerFactory> factories = ServiceLoader.load(MixinServerHandshakerFactory.class);
        for (MixinServerHandshakerFactory factory : factories) {
            MixinServerHandshaker handshaker = factory.createHandshaker(null, null);
            System.out.println(handshaker);
        }
    }

}