package com.im.oldchat.lua;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;

import com.im.oldchat.api.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class LuaAppSyncManager {
    public interface Callback { void onComplete(List<LuaMiniApp> apps); }
    private static final Object LOCK = new Object();
    private static boolean running;
    private static final List<Callback> callbacks = new ArrayList<Callback>();

    private LuaAppSyncManager() { }

    public static void sync(Context context, Callback callback) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (callback != null) callbacks.add(callback);
            if (running) return;
            running = true;
        }
        SharedPreferences auth = app.getSharedPreferences("auth", Context.MODE_PRIVATE);
        String savedToken = auth.getString("access_token", "");
        if (savedToken == null || savedToken.length() == 0) {
            savedToken = auth.getString("token", "");
        }
        final String token = savedToken;
        if (token == null || token.length() == 0) {
            finish(app);
            return;
        }
        HttpUtil.get("/discover/lua/manifest", token, new HttpUtil.Callback() {
            @Override public void onSuccess(String response) {
                try {
                    JSONObject root = new JSONObject(response);
                    JSONArray array = root.optJSONArray("apps");
                    final List<LuaMiniApp> remote = new ArrayList<LuaMiniApp>();
                    if (array != null) for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.optJSONObject(i);
                        LuaMiniApp item = parse(obj);
                        if (item != null && item.enabled) remote.add(item);
                    }
                    syncNext(app, token, remote, 0);
                } catch (Exception e) { finish(app); }
            }
            @Override public void onError(int code, String error) { finish(app); }
        });
    }

    private static void syncNext(final Context app, final String token,
                                 final List<LuaMiniApp> remote, final int index) {
        if (index >= remote.size()) {
            LuaAppStore.saveApps(app, remote);
            finish(app);
            return;
        }
        final LuaMiniApp item = remote.get(index);
        String cached = LuaAppStore.readScript(app, item.id);
        if (cached != null && item.sha256.equalsIgnoreCase(sha256(cached))) {
            syncNext(app, token, remote, index + 1);
            return;
        }
        HttpUtil.get(item.script_url, token, new HttpUtil.Callback() {
            @Override public void onSuccess(final String response) {
                new AsyncTask<Void, Void, Boolean>() {
                    String script;
                    @Override protected Boolean doInBackground(Void... values) {
                        try {
                            JSONObject root = new JSONObject(response);
                            script = root.optString("script", "");
                            String hash = root.optString("sha256", "");
                            return script.length() > 0 && script.length() <= 512 * 1024
                                    && hash.equalsIgnoreCase(item.sha256)
                                    && hash.equalsIgnoreCase(sha256(script))
                                    && LuaAppStore.writeScriptAtomic(app, item.id, script);
                        } catch (Exception ignored) { return false; }
                    }
                    @Override protected void onPostExecute(Boolean ok) {
                        if (!ok && LuaAppStore.readScript(app, item.id) == null) remote.remove(index);
                        syncNext(app, token, remote, ok || index >= remote.size() ? index + 1 : index);
                    }
                }.execute();
            }
            @Override public void onError(int code, String error) {
                if (LuaAppStore.readScript(app, item.id) == null) remote.remove(index);
                syncNext(app, token, remote, index < remote.size() ? index : index + 1);
            }
        });
    }

    private static LuaMiniApp parse(JSONObject obj) {
        if (obj == null) return null;
        LuaMiniApp item = new LuaMiniApp();
        item.id = obj.optString("id", "");
        if (!item.id.matches("[a-z0-9_-]{1,64}")) return null;
        item.name = obj.optString("name", item.id);
        item.description = obj.optString("description", "");
        item.version = obj.optInt("version", 1);
        item.icon_url = obj.optString("icon_url", "");
        item.enabled = obj.optBoolean("enabled", true);
        item.order = obj.optInt("order", 0);
        item.sha256 = obj.optString("sha256", "");
        item.script_url = obj.optString("script_url", "");
        if (!item.script_url.startsWith("/discover/lua/apps/") || item.sha256.length() != 64) return null;
        JSONArray permissions = obj.optJSONArray("permissions");
        if (permissions != null) for (int i = 0; i < permissions.length(); i++) {
            String value = permissions.optString(i, "");
            if ("camera".equals(value) || "network".equals(value) || "network_external".equals(value) || "storage".equals(value)) item.permissions.add(value);
        }
        JSONArray hosts = obj.optJSONArray("allowed_hosts");
        if (hosts != null) for (int i = 0; i < hosts.length() && i < 32; i++) {
            String host = hosts.optString(i, "").trim().toLowerCase(java.util.Locale.US);
            if ("*".equals(host) || host.matches("(\\*\\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) item.allowedHosts.add(host);
        }
        return item;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) { String h = Integer.toHexString(b & 255); if (h.length() == 1) out.append('0'); out.append(h); }
            return out.toString();
        } catch (Exception ignored) { return ""; }
    }

    private static void finish(Context app) {
        List<Callback> notify;
        synchronized (LOCK) { running = false; notify = new ArrayList<Callback>(callbacks); callbacks.clear(); }
        List<LuaMiniApp> apps = LuaAppStore.getApps(app);
        for (int i = 0; i < notify.size(); i++) try { notify.get(i).onComplete(apps); } catch (Exception ignored) { }
    }
}
