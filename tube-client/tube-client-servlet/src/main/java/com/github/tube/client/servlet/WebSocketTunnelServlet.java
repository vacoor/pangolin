package com.github.tube.client.servlet;

import com.github.tube.client.WebSocketTunnelClient;

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
@WebServlet(urlPatterns = "/api/debug")
public class WebSocketTunnelServlet extends HttpServlet {
    private static final String KEY = WebSocketTunnelClient.class.getName();

    @Override
    public void init() throws ServletException {
        super.init();
    }

    @Override
    protected void doGet(final HttpServletRequest httpRequest, final HttpServletResponse httpResponse) throws ServletException, IOException {
        final String server = httpRequest.getParameter("server");
        if (null != server) {
            final URI uri = URI.create(server);

            final ServletContext context = httpRequest.getServletContext();
            final WebSocketTunnelClient client = (WebSocketTunnelClient) context.getAttribute(KEY);
            if (null != client) {
                client.shutdownGracefully();
            }
            final WebSocketTunnelClient newClient = new WebSocketTunnelClient("default", uri);
            try {
                newClient.start();
                context.setAttribute(KEY, newClient);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            httpResponse.getWriter().write("CLIENT_START");
        } else {
            httpResponse.getWriter().write("NOT_FOUND_SERVER");
        }
    }
}
