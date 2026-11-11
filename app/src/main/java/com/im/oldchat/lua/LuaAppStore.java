package com.im.oldchat.lua;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class LuaAppStore {
    private static final String PREFS = "lua_mini_apps";
    private static final String KEY_APPS = "apps";
    private static final String KEY_LOCAL_APPS = "local_apps";
    private static final Gson GSON = new Gson();

    private LuaAppStore() { }

    public static List<LuaMiniApp> getApps(Context context) {
        if (context == null) return new ArrayList<LuaMiniApp>();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = preferences.getString(KEY_APPS, "[]");
        List<LuaMiniApp> out;
        try {
            out = GSON.fromJson(json, new TypeToken<List<LuaMiniApp>>() { }.getType());
            if (out == null) out = new ArrayList<LuaMiniApp>();
        } catch (Exception ignored) {
            out = new ArrayList<LuaMiniApp>();
        }
        // 只要列表为空就重新修复内置示例，避免旧版本遗留的空缓存永久隐藏入口。
        if (out.isEmpty()) {
            List<LuaMiniApp> bootstrap = installBootstrap(context);
            preferences.edit().putString(KEY_APPS, GSON.toJson(bootstrap)).commit();
            out = bootstrap;
        }
        return mergeLocalApps(context, out);
    }

    private static List<LuaMiniApp> mergeLocalApps(Context context, List<LuaMiniApp> remote) {
        java.util.LinkedHashMap<String, LuaMiniApp> merged = new java.util.LinkedHashMap<String, LuaMiniApp>();
        if (remote != null) for (LuaMiniApp app : remote) {
            if (app != null && app.id != null) merged.put(app.id, app);
        }
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOCAL_APPS, "[]");
        try {
            List<LuaMiniApp> local = GSON.fromJson(json, new TypeToken<List<LuaMiniApp>>() { }.getType());
            if (local != null) for (LuaMiniApp app : local) {
                if (app != null && app.id != null) merged.put(app.id, app);
            }
        } catch (Exception ignored) { }
        // 文件元数据是本地应用的权威来源，避免 R8/Gson 字段变化或偏好写入竞争导致入口消失。
        File metadataDir = localMetadataDir(context);
        File[] metadataFiles = metadataDir.listFiles();
        if (metadataFiles != null) for (File file : metadataFiles) {
            if (file == null || !file.isFile() || !file.getName().endsWith(".json")) continue;
            LuaMiniApp app = readLocalMetadata(file);
            if (app != null && app.id != null && readScript(context, app.id) != null) merged.put(app.id, app);
        }
        return new ArrayList<LuaMiniApp>(merged.values());
    }

    static boolean saveLocalApp(Context context, LuaMiniApp item) {
        if (context == null || item == null || item.id == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<LuaMiniApp> local = new ArrayList<LuaMiniApp>();
        try {
            List<LuaMiniApp> parsed = GSON.fromJson(prefs.getString(KEY_LOCAL_APPS, "[]"),
                    new TypeToken<List<LuaMiniApp>>() { }.getType());
            if (parsed != null) local.addAll(parsed);
        } catch (Exception ignored) { }
        for (int i = local.size() - 1; i >= 0; i--) {
            if (item.id.equals(local.get(i).id)) local.remove(i);
        }
        local.add(item);
        if (!writeLocalMetadata(context, item)) return false;
        return prefs.edit().putString(KEY_LOCAL_APPS, GSON.toJson(local)).commit();
    }

    public static boolean deleteLocalApp(Context context, String id) {
        if (context == null || id == null || !id.startsWith("local_")) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<LuaMiniApp> local = new ArrayList<LuaMiniApp>();
        try {
            List<LuaMiniApp> parsed = GSON.fromJson(prefs.getString(KEY_LOCAL_APPS, "[]"),
                    new TypeToken<List<LuaMiniApp>>() { }.getType());
            if (parsed != null) for (LuaMiniApp app : parsed) if (app != null && !id.equals(app.id)) local.add(app);
        } catch (Exception ignored) { }
        boolean committed = prefs.edit().putString(KEY_LOCAL_APPS, GSON.toJson(local)).commit();
        File metadata = new File(localMetadataDir(context), id + ".json");
        if (metadata.exists()) metadata.delete();
        File script = scriptFile(context, id); if (script.exists()) script.delete();
        deleteRecursively(localAssetDir(context, id));
        return committed;
    }

    private static File localMetadataDir(Context context) {
        return new File(new File(context.getFilesDir(), "lua_apps"), "local_metadata");
    }

    private static boolean writeLocalMetadata(Context context, LuaMiniApp app) {
        try {
            File dir = localMetadataDir(context); if (!dir.exists() && !dir.mkdirs()) return false;
            JSONObject obj = new JSONObject();
            obj.put("id", app.id); obj.put("name", app.name); obj.put("description", app.description);
            obj.put("version", app.version); obj.put("icon_url", app.icon_url); obj.put("enabled", app.enabled); obj.put("order", app.order);
            JSONArray permissions = new JSONArray(); if (app.permissions != null) for (String value : app.permissions) permissions.put(value);
            obj.put("permissions", permissions);
            JSONArray hosts = new JSONArray(); if (app.allowedHosts != null) for (String value : app.allowedHosts) hosts.put(value);
            obj.put("allowed_hosts", hosts);
            File target = new File(dir, app.id + ".json"); File temp = new File(dir, app.id + ".json.tmp");
            FileOutputStream output = new FileOutputStream(temp, false); output.write(obj.toString().getBytes("UTF-8")); output.close();
            if (target.exists() && !target.delete()) return false;
            return temp.renameTo(target);
        } catch (Exception ignored) { return false; }
    }

    private static LuaMiniApp readLocalMetadata(File file) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            JSONObject obj = new JSONObject(new String(output.toByteArray(), "UTF-8"));
            LuaMiniApp app = new LuaMiniApp(); app.id = obj.optString("id", "");
            if (!app.id.matches("local_[a-z0-9_-]{1,64}")) return null;
            app.name = obj.optString("name", app.id); app.description = obj.optString("description", ""); app.version = obj.optInt("version", 1);
            app.icon_url = obj.optString("icon_url", ""); app.enabled = obj.optBoolean("enabled", true); app.order = obj.optInt("order", 0);
            JSONArray permissions = obj.optJSONArray("permissions"); if (permissions != null) for (int i = 0; i < permissions.length(); i++) app.permissions.add(permissions.optString(i));
            JSONArray hosts = obj.optJSONArray("allowed_hosts"); if (hosts != null) for (int i = 0; i < hosts.length(); i++) app.allowedHosts.add(hosts.optString(i));
            return app;
        } catch (Exception ignored) { return null; }
        finally { if (input != null) try { input.close(); } catch (Exception ignored) { } }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] files = file.listFiles(); if (files != null) for (File child : files) deleteRecursively(child); }
        file.delete();
    }

    public static File localAssetDir(Context context, String id) {
        return new File(new File(new File(context.getFilesDir(), "lua_apps"), "assets"), id);
    }

    private static List<LuaMiniApp> installBootstrap(Context context) {
        List<LuaMiniApp> apps = new ArrayList<LuaMiniApp>();
        java.io.InputStream input = null;
        try {
            input = context.getAssets().open("lua_apps/welcome.lua");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            String script = new String(output.toByteArray(), "UTF-8");
            if (!writeScriptAtomic(context, "welcome", script)) return apps;
            LuaMiniApp item = new LuaMiniApp();
            item.id = "welcome";
            item.name = "Lua 示例";
            item.description = "服务器功能与 Android 能力示例";
            item.version = 1;
            item.enabled = true;
            item.order = 100;
            item.permissions.add("camera");
            item.permissions.add("network");
            item.permissions.add("storage");
            apps.add(item);
        } catch (Exception ignored) {
            // 资源异常时保持空列表，后续服务器同步仍可恢复。
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
        return apps;
    }

    static void saveApps(Context context, List<LuaMiniApp> apps) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_APPS, GSON.toJson(apps == null ? new ArrayList<LuaMiniApp>() : apps)).apply();
    }

    public static LuaMiniApp find(Context context, String id) {
        List<LuaMiniApp> apps = getApps(context);
        for (int i = 0; i < apps.size(); i++) {
            LuaMiniApp app = apps.get(i);
            if (app != null && app.id != null && app.id.equals(id)) return app;
        }
        return null;
    }

    public static String readScript(Context context, String id) {
        File file = scriptFile(context, id);
        if (!file.isFile() || file.length() <= 0 || file.length() > 512 * 1024) return null;
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            return new String(output.toByteArray(), "UTF-8");
        } catch (Exception ignored) {
            return null;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
    }

    static boolean writeScriptAtomic(Context context, String id, String script) {
        File target = scriptFile(context, id);
        File dir = target.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) return false;
        File temp = new File(dir, id + ".lua.tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temp, false);
            output.write(script.getBytes("UTF-8"));
            output.flush();
            try { output.getFD().sync(); } catch (Exception ignored) { }
            output.close();
            output = null;
            if (target.exists() && !target.delete()) return false;
            return temp.renameTo(target);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
            if (temp.exists() && !target.exists()) temp.delete();
        }
    }

    private static File scriptFile(Context context, String id) {
        String safe = id == null ? "" : id.replaceAll("[^a-z0-9_-]", "");
        return new File(new File(context.getFilesDir(), "lua_apps"), safe + ".lua");
    }
}
