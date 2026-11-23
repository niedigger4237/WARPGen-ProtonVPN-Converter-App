package com.local.protonawg;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.IOException;
import java.util.Collections;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    static final int SAVE = 1;
    private static final int OPEN = 2;
    private static final String ORIGIN = "https://localhost";
    private WebView web;
    private NativeActions actions;
    private ValueCallback<Uri[]> files;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            TextView notice = new TextView(this);
            notice.setText("Обновите Android System WebView, чтобы открыть приложение.");
            setContentView(notice);
            return;
        }
        actions = new NativeActions(this);
        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.addView(web);
        setContentView(root);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame() && (ORIGIN + "/").equals(r.getUrl().toString())) {
                    try { return new WebResourceResponse("text/html", "UTF-8", getAssets().open("index.html")); }
                    catch (IOException e) { throw new IllegalStateException(e); }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", Collections.emptyMap(), null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (files != null) files.onReceiveValue(null);
                files = callback;
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                open.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(open, OPEN); }
                catch (android.content.ActivityNotFoundException e) { files.onReceiveValue(null); files = null; }
                return true;
            }
        });
        WebViewCompat.addWebMessageListener(web, "AndroidHost", Collections.singleton(ORIGIN),
            (view, message, origin, mainFrame, reply) -> {
                if (!mainFrame || !ORIGIN.equals(origin.toString())) return;
                try {
                    JSONObject req = new JSONObject(message.getData());
                    NativeCall call = new NativeCall(this, reply, req.getInt("id"), req.getJSONObject("args"));
                    switch (req.getString("method")) {
                        case "request": actions.request(call); break;
                        case "saveFiles": actions.saveFiles(call); break;
                        case "shareFiles": actions.shareFiles(call); break;
                        case "dpiAvailable": actions.dpiAvailable(call); break;
                        case "dpiStart": actions.dpiStart(call); break;
                        case "dpiStop": actions.dpiStop(call); break;
                        case "dpiStatus": actions.dpiStatus(call); break;
                        case "dpiProbe": actions.dpiProbe(call); break;
                        default: call.reject("Неизвестный метод");
                    }
                } catch (org.json.JSONException ignored) { /* Invalid bridge envelope. */ }
            });
        web.loadUrl(ORIGIN + "/");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE && actions != null) actions.saveResult(result, data);
        if (request != OPEN || files == null) return;
        Uri[] selected = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                selected = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < selected.length; i++) selected[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) selected = new Uri[]{data.getData()};
        }
        files.onReceiveValue(selected);
        files = null;
    }

    @Override protected void onDestroy() {
        if (files != null) files.onReceiveValue(null);
        if (actions != null) actions.close();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
