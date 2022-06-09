package com.github.pangolin.client.servlet;

import com.github.pangolin.client.WebSocketTunnelClient;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

/**
 *
 */
@WebServlet(urlPatterns = "/api/tunnel")
public class WebSocketTunnelServlet extends HttpServlet {
    private static final String TUNNEL_KEY = WebSocketTunnelClient.class.getName();

    @Override
    protected void doGet(final HttpServletRequest httpRequest, final HttpServletResponse httpResponse) throws ServletException, IOException {
        final String server = httpRequest.getParameter("server");
        if (null != server) {
            final URI serverUrl = URI.create(server);
            final ServletContext context = httpRequest.getServletContext();
            final WebSocketTunnelClient client = (WebSocketTunnelClient) context.getAttribute(TUNNEL_KEY);
            if (null != client) {
                if (client.isRunning() && serverUrl.equals(client.getServerEndpoint())) {
                    httpResponse.getWriter().write("READY");
                    return;
                }
                client.shutdownGracefully();
            }

            final String localAddr = httpRequest.getLocalAddr();
            final int localPort = httpRequest.getLocalPort();
            final WebSocketTunnelClient newClient = new WebSocketTunnelClient(localAddr + "." + localPort, serverUrl);
            try {
                newClient.start();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            context.setAttribute(TUNNEL_KEY, newClient);
            httpResponse.getWriter().write("CLIENT_START");
        } else {
            httpResponse.getWriter().write("NOT_FOUND_SERVER");
        }
    }
}
