package com.local.protonawg;

import android.app.Activity;
import androidx.webkit.JavaScriptReplyProxy;
import org.json.JSONException;
import org.json.JSONObject;

final class NativeCall {
    final JSONObject args;
    private final Activity activity;
    private final JavaScriptReplyProxy reply;
    private final int id;

    NativeCall(Activity activity, JavaScriptReplyProxy reply, int id, JSONObject args) {
        this.activity = activity;
        this.reply = reply;
        this.id = id;
        this.args = args;
    }

    void resolve(JSONObject result) { send("result", result); }
    void resolve() { resolve(new JSONObject()); }
    void reject(String message) { send("error", message == null ? "Ошибка Android" : message); }

    void resolve(String key, boolean value) {
        try { resolve(new JSONObject().put(key, value)); }
        catch (JSONException e) { reject(e.getMessage()); }
    }

    private void send(String key, Object value) {
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || activity.isFinishing()) return;
            try { reply.postMessage(new JSONObject().put("id", id).put(key, value).toString()); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }
}
