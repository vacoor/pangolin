package com.github.pangolin.routing.server;

import com.github.pangolin.routing.util.IOUtils;
import freework.codec.Base64;
import freework.net.Http;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;

/**
 *
 */
public class Subscribe {
    public static void main(String[] args) throws IOException {
        String url = "https://sub1.smallstrawberry.com/api/v1/client/subscribe?token=1ab79cc4b202d916cdc8e375c7b03266";
        HttpURLConnection httpUrlConnection = null;
        try {
            httpUrlConnection = Http.get(url);
            httpUrlConnection.setRequestProperty("Accept", "application/json, text/plain, */*");
            httpUrlConnection.setRequestProperty("pragma", "No-Cache");
            httpUrlConnection.setRequestProperty("User-Agent", "ClashforWindows/0.19.25");
            final int responseCode = httpUrlConnection.getResponseCode();
            List<String> read = IOUtils.read(httpUrlConnection.getInputStream());
            // final InputStream in = Base64.wrap(httpUrlConnection.getInputStream(), false);
//            final List<String> read = IOUtils.read(in);
            System.out.println(read);
        } finally {
            Http.close(httpUrlConnection);
        }
    }
}
