package com.local.protonawg;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTP-запрос к API Cloudflare через http/socks5-прокси (стандартный fetch/CapacitorHttp прокси не поддерживают). */
@CapacitorPlugin(name = "WarpHttp")
public class WarpHttpPlugin extends Plugin {

    @PluginMethod
    public void request(final PluginCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = call.getString("url");
                    String method = call.getString("method", "GET");
                    String body = call.getString("body", "");
                    String proxyStr = call.getString("proxy", "");
                    if (url == null || !url.startsWith("https://api.cloudflareclient.com/")) {
                        call.reject("Недопустимый адрес");
                        return;
                    }

                    OkHttpClient.Builder cb = new OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS);

                    if (proxyStr != null && !proxyStr.isEmpty()) {
                        URI pu = new URI(proxyStr);
                        String scheme = pu.getScheme() == null ? "" : pu.getScheme().toLowerCase();
                        boolean socks = scheme.startsWith("socks");
                        String host = pu.getHost();
                        if (host == null) {
                            call.reject("Неверный адрес прокси");
                            return;
                        }
                        int port = pu.getPort() > 0 ? pu.getPort() : (socks ? 1080 : 8080);
                        cb.proxy(new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)));

                        String ui = pu.getRawUserInfo();
                        if (ui != null && !ui.isEmpty()) {
                            int i = ui.indexOf(':');
                            final String user = URLDecoder.decode(i < 0 ? ui : ui.substring(0, i), "UTF-8");
                            final String pass = i < 0 ? "" : URLDecoder.decode(ui.substring(i + 1), "UTF-8");
                            if (socks) {
                                Authenticator.setDefault(new Authenticator() {
                                    @Override
                                    protected PasswordAuthentication getPasswordAuthentication() {
                                        return new PasswordAuthentication(user, pass.toCharArray());
                                    }
                                });
                            } else {
                                cb.proxyAuthenticator(new okhttp3.Authenticator() {
                                    @Override
                                    public Request authenticate(okhttp3.Route route, Response response) {
                                        return response.request().newBuilder()
                                                .header("Proxy-Authorization", Credentials.basic(user, pass))
                                                .build();
                                    }
                                });
                            }
                        }
                    }

                    Request.Builder rb = new Request.Builder().url(url);
                    JSObject headers = call.getObject("headers");
                    if (headers != null) {
                        Iterator<String> it = headers.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (!"Content-Type".equalsIgnoreCase(k)) {
                                rb.header(k, headers.getString(k));
                            }
                        }
                    }
                    RequestBody rbody = null;
                    if (!"GET".equals(method)) {
                        rbody = RequestBody.create(body == null ? "" : body, MediaType.parse("application/json; charset=utf-8"));
                    }
                    rb.method(method, rbody);

                    try (Response r = cb.build().newCall(rb.build()).execute()) {
                        JSObject res = new JSObject();
                        res.put("status", r.code());
                        res.put("data", r.body() == null ? "" : r.body().string());
                        call.resolve(res);
                    }
                } catch (Exception e) {
                    call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }).start();
    }
}
