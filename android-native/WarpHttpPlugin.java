package com.local.protonawg;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
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

    /* ---------- ByeDPI: исполняемый файл libciadpi.so из nativeLibraryDir ---------- */
    private static final Pattern OK_TOKEN = Pattern.compile("^[-A-Za-z0-9_.,:+=@%]+$");
    private static final Pattern BAD_FLAG = Pattern.compile("^(-i|--ip|-p|--port|-I|--conn-ip|-D|--daemon|-w|--pidfile|-E|--transparent|-H|--hosts|-j|--ipset)(=.*)?$");
    private static final Pattern PROBE_URL = Pattern.compile("^https://[A-Za-z0-9.-]+/$");
    private static Process dpiProc = null;

    private File dpiFile() {
        return new File(getContext().getApplicationInfo().nativeLibraryDir, "libciadpi.so");
    }

    static synchronized void killDpiStatic() {
        if (dpiProc != null) {
            try {
                dpiProc.destroy();
            } catch (Exception ignored) {
            }
            dpiProc = null;
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void killDpi() {
        killDpiStatic();
    }

    private void stopNotificationService() {
        try {
            getContext().stopService(new Intent(getContext(), DpiService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void handleOnDestroy() {
        killDpi();
        stopNotificationService();
        super.handleOnDestroy();
    }

    private void showNotification(String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                    && ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{"android.permission.POST_NOTIFICATIONS"}, 4712);
            }
            Intent i = new Intent(getContext(), DpiService.class);
            i.putExtra("text", text == null ? "" : text);
            ContextCompat.startForegroundService(getContext(), i);
        } catch (Exception ignored) {
            // уведомление необязательно: прокси работает и без него
        }
    }

    @PluginMethod
    public void dpiAvailable(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", dpiFile().exists());
        call.resolve(r);
    }

    @PluginMethod
    public void dpiStatus(PluginCall call) {
        boolean running = false;
        synchronized (WarpHttpPlugin.class) {
            if (dpiProc != null) {
                try {
                    dpiProc.exitValue();
                    dpiProc = null; // процесс завершился
                } catch (IllegalThreadStateException alive) {
                    running = true;
                }
            }
        }
        if (!running) {
            stopNotificationService();
        }
        JSObject r = new JSObject();
        r.put("running", running);
        call.resolve(r);
    }

    @PluginMethod
    public void dpiStop(PluginCall call) {
        killDpi();
        stopNotificationService();
        call.resolve();
    }

    @PluginMethod
    public void dpiStart(final PluginCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File exe = dpiFile();
                    if (!exe.exists()) {
                        call.reject("Движок ByeDPI не найден в сборке");
                        return;
                    }
                    Integer port = call.getInt("port");
                    if (port == null || port < 1024 || port > 65535) {
                        call.reject("Недопустимый порт");
                        return;
                    }
                    List<String> cmd = new ArrayList<String>();
                    cmd.add(exe.getAbsolutePath());
                    cmd.add("-i");
                    cmd.add("127.0.0.1");
                    cmd.add("-p");
                    cmd.add(String.valueOf(port));
                    JSArray arr = call.getArray("args");
                    if (arr != null) {
                        if (arr.length() > 60) {
                            call.reject("Слишком много параметров");
                            return;
                        }
                        for (int i = 0; i < arr.length(); i++) {
                            String a = arr.getString(i);
                            if (!OK_TOKEN.matcher(a).matches() || BAD_FLAG.matcher(a).matches()) {
                                call.reject("Недопустимый параметр: " + a);
                                return;
                            }
                            cmd.add(a);
                        }
                    }
                    killDpi();
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    final Process pr = pb.start();
                    final StringBuilder out = new StringBuilder();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                InputStream in = pr.getInputStream();
                                byte[] buf = new byte[512];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    if (out.length() < 600) {
                                        synchronized (out) {
                                            out.append(new String(buf, 0, n, "UTF-8"));
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }).start();
                    Thread.sleep(450);
                    try {
                        int code = pr.exitValue();
                        String msg;
                        synchronized (out) {
                            msg = out.toString().trim();
                        }
                        call.reject("ByeDPI завершился (код " + code + "): " + (msg.isEmpty() ? "порт занят или неверные параметры" : (msg.length() > 200 ? msg.substring(0, 200) : msg)));
                        return;
                    } catch (IllegalThreadStateException stillRunning) {
                        // процесс работает
                    }
                    synchronized (WarpHttpPlugin.class) {
                        dpiProc = pr;
                    }
                    if (Boolean.TRUE.equals(call.getBoolean("notify", false))) {
                        showNotification(call.getString("text", ""));
                    }
                    JSObject res = new JSObject();
                    res.put("running", true);
                    call.resolve(res);
                } catch (Exception e) {
                    call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }).start();
    }

    /** Проверка доступности сайта через локальный SOCKS5: достаточно любого HTTP-ответа по HTTPS. */
    @PluginMethod
    public void dpiProbe(final PluginCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String url = call.getString("url");
                Integer port = call.getInt("port");
                Integer timeout = call.getInt("timeout", 6000);
                JSObject res = new JSObject();
                if (url == null || !PROBE_URL.matcher(url).matches() || port == null) {
                    call.reject("Недопустимый запрос");
                    return;
                }
                long t0 = System.currentTimeMillis();
                try {
                    int tm = Math.min(Math.max(timeout, 1000), 20000);
                    OkHttpClient c = new OkHttpClient.Builder()
                            .proxy(new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", port)))
                            .connectTimeout(tm, TimeUnit.MILLISECONDS)
                            .readTimeout(tm, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .followRedirects(false)
                            .build();
                    Request rq = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
                    try (Response r = c.newCall(rq).execute()) {
                        res.put("ok", true);
                        res.put("ms", (int) (System.currentTimeMillis() - t0));
                        res.put("status", r.code());
                    }
                } catch (Exception e) {
                    res.put("ok", false);
                    res.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                }
                call.resolve(res);
            }
        }).start();
    }

    /* ---------- Сохранение конфигов: системный выбор файла или папки ---------- */
    @PluginMethod
    public void saveFiles(PluginCall call) {
        JSArray files = call.getArray("files");
        if (files == null || files.length() == 0) {
            call.reject("Нет файлов");
            return;
        }
        try {
            Intent intent;
            if (files.length() == 1) {
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, files.getJSONObject(0).getString("name"));
            } else {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            }
            startActivityForResult(call, intent, "saveResult");
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @ActivityCallback
    private void saveResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject res = new JSObject();
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            res.put("canceled", true);
            call.resolve(res);
            return;
        }
        try {
            Uri uri = result.getData().getData();
            JSArray files = call.getArray("files");
            ContentResolver cr = getContext().getContentResolver();
            int count = 0;
            if (files.length() == 1) {
                writeText(cr, uri, files.getJSONObject(0).getString("text"));
                count = 1;
            } else {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    Uri doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", f.getString("name"));
                    if (doc != null) {
                        writeText(cr, doc, f.getString("text"));
                        count++;
                    }
                }
            }
            res.put("canceled", false);
            res.put("count", count);
            call.resolve(res);
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void writeText(ContentResolver cr, Uri uri, String text) throws Exception {
        OutputStream os = cr.openOutputStream(uri, "wt");
        if (os == null) {
            throw new Exception("Не удалось открыть файл для записи");
        }
        try {
            os.write(text.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }
}
