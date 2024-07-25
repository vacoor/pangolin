package com.github.pangolin.routing.upstream.spi;

import com.github.pangolin.routing.handler.codec.ss.crypto.CipherAlgorithm;
import com.github.pangolin.routing.handler.codec.ss.crypto.spi.CipherAlgorithmSpi;
import com.github.pangolin.routing.handler.internal.client.SsDatagramProxyHandler;
import com.github.pangolin.routing.handler.internal.client.SsProxyHandler;
import com.github.pangolin.routing.upstream.Upstream;
import com.github.pangolin.routing.upstream.AbstractUpstream;
import freework.codec.Base64;
import freework.util.Bytes;
import io.netty.channel.ChannelHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

public class SsUpstreamFactory extends AbstractUpstreamFactory {
    private static final int DEFAULT_SS_PORT = 8388;

    public SsUpstreamFactory() {
        super(new String[]{"ss://"});
    }

    @Override
    protected Upstream apply0(final String name, final String serverUrl) {
        /*-
         * <pre>
         * SS-URI = "ss://" userinfo "@" hostname ":" port [ "/" ] [ "?" plugin ] [ "#" tag ]
         * userinfo = websafe-base64-encode-utf8(method  ":" password)
         *            method ":" password
         * </pre>
         *
         * @see <a href="#">SIP002 URI Scheme</a>
         */
        final URI uri = URI.create(serverUrl);
        final String nameToUse = null != name ? name : uri.getFragment();

        final InetSocketAddress address = toSocketAddress(
                uri.getHost(), determinePort(uri.getPort())
        );

        final String[] userInfo = splitUserInfo(uri.getUserInfo());
        if (2 != userInfo.length) {
            throw new IllegalArgumentException("SS method & password must not be null");
        }

        final CipherAlgorithm cipher = CipherAlgorithmSpi.getInstance(userInfo[0]);
        return new SsUpstream(nameToUse, address, cipher, userInfo[1]);
    }

    private int determinePort(final int port) {
        return port > 0 ? port : DEFAULT_SS_PORT;
    }

    @Override
    protected String[] splitUserInfo(final String userInfo) {
        /*-
         * With user info encoded with Base64URL.
         */
        String userInfoToUse = userInfo;
        if (null != userInfoToUse && !userInfoToUse.contains(":")) {
            userInfoToUse = Bytes.toString(Base64.decode(userInfoToUse, true));
        }
        return super.splitUserInfo(userInfoToUse);
    }

    private class SsUpstream extends AbstractUpstream {
        private final SocketAddress address;
        private final CipherAlgorithm algorithm;
        private final String password;

        SsUpstream(final String name, final SocketAddress address, final CipherAlgorithm algorithm, final String password) {
            super(name);
            this.address = address;
            this.algorithm = algorithm;
            this.password = password;
        }

        @Override
        public ChannelHandler newSocketProxyHandler(InetSocketAddress destination) {
            return new SsProxyHandler(address, algorithm, password);
        }

        @Override
        public ChannelHandler newDatagramProxyHandler(final InetSocketAddress destination) {
            return new SsDatagramProxyHandler((InetSocketAddress) address, algorithm, password);
        }
    }
}
