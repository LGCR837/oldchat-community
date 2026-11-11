package com.im.oldchat.lua;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LocalCipImporter {
    private static final int MAX_PACKAGE = 2 * 1024 * 1024;
    private static final int MAX_EXTRACTED = 8 * 1024 * 1024;
    private static final int MAX_FILES = 128;

    private LocalCipImporter() { }

    public static LuaMiniApp importPackage(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) throw new Exception("未选择 CIP 文件");
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new Exception("无法读取 CIP 文件");
        byte[] archive;
        try { archive = readLimited(input, MAX_PACKAGE); }
        finally { try { input.close(); } catch (Exception ignored) { } }
        return importPackageFromBytes(context, archive);
    }

    public static LuaMiniApp importPackageFromBytes(Context context, byte[] archive) throws Exception {
        if (context == null || archive == null || archive.length == 0) throw new Exception("CIP 文件为空");
        // 兼容用户把 .cip 再压成 .zip 的情况：自动剥离最多两层单文件归档。
        for (int depth = 0; depth < 2; depth++) {
            byte[] nested = unwrapSingleArchive(archive);
            if (nested == archive) break;
            archive = nested;
        }

        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive));
        int count = 0;
        int total = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
            if (!safePath(name)) throw new Exception("CIP 包含不安全路径");
            if (!"manifest.json".equals(name) && !"main.lua".equals(name)) {
                if (!name.startsWith("assets/") || !allowedAsset(name))
                    throw new Exception("不允许的文件：" + name);
            }
            if (++count > MAX_FILES) throw new Exception("CIP 文件数量超过 128");
            byte[] data = readLimited(zip, MAX_EXTRACTED - total);
            total += data.length;
            if (total > MAX_EXTRACTED) throw new Exception("CIP 解压后超过 8 MiB");
            files.put(name, data);
            zip.closeEntry();
        }
        zip.close();
        byte[] manifest = files.get("manifest.json");
        byte[] scriptBytes = files.get("main.lua");
        if (manifest == null || scriptBytes == null || scriptBytes.length == 0 || scriptBytes.length > 512 * 1024)
            throw new Exception("缺少 manifest.json 或 main.lua");

        JSONObject obj = new JSONObject(new String(manifest, "UTF-8"));
        LuaMiniApp app = new LuaMiniApp();
        String packageId = obj.optString("id", "");
        if (!packageId.matches("[a-z0-9_-]{1,58}")) throw new Exception("应用 ID 不合法");
        // 本地应用使用独立命名空间，避免服务器同步覆盖同名脚本。
        app.id = packageId.startsWith("local_") ? packageId : "local_" + packageId;
        app.name = obj.optString("name", "").trim();
        if (app.name.length() == 0 || app.name.length() > 40) throw new Exception("应用名称不合法");
        app.description = obj.optString("description", "");
        app.version = obj.optInt("version", 0);
        if (app.version <= 0) throw new Exception("版本必须为正整数");
        app.enabled = true;
        app.order = obj.optInt("order", 0);
        app.icon_url = obj.optString("icon_url", "");
        JSONArray permissions = obj.optJSONArray("permissions");
        if (permissions != null) for (int i = 0; i < permissions.length(); i++) {
            String permission = permissions.optString(i, "");
            if (!"camera".equals(permission) && !"network".equals(permission) && !"network_external".equals(permission) && !"storage".equals(permission))
                throw new Exception("不支持的权限：" + permission);
            if (!app.permissions.contains(permission)) app.permissions.add(permission);
        }
        JSONArray hosts = obj.optJSONArray("allowed_hosts");
        if (hosts != null) for (int i = 0; i < hosts.length(); i++) {
            String host = hosts.optString(i, "").trim().toLowerCase(java.util.Locale.US);
            if (!"*".equals(host) && !host.matches("(\\*\\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) throw new Exception("allowed_hosts 域名不合法：" + host);
            if (!app.allowedHosts.contains(host)) app.allowedHosts.add(host);
        }
        String script = new String(scriptBytes, "UTF-8");
        if (!LuaAppStore.writeScriptAtomic(context, app.id, script)) throw new Exception("脚本保存失败");
        File assetTarget = LuaAppStore.localAssetDir(context, app.id);
        File stage = new File(assetTarget.getParentFile(), app.id + ".tmp");
        delete(stage);
        if (!stage.mkdirs() && !stage.isDirectory()) throw new Exception("资源目录创建失败");
        try {
            for (Map.Entry<String, byte[]> item : files.entrySet()) {
                if (!item.getKey().startsWith("assets/")) continue;
                String relative = item.getKey().substring("assets/".length());
                File target = new File(stage, relative);
                if (!target.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator))
                    throw new Exception("资源路径越界");
                File parent = target.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) throw new Exception("资源目录创建失败");
                FileOutputStream output = new FileOutputStream(target);
                output.write(item.getValue()); output.close();
            }
            delete(assetTarget);
            if (!stage.renameTo(assetTarget)) throw new Exception("资源保存失败");
        } finally { if (stage.exists()) delete(stage); }
        if (app.icon_url.startsWith("assets/")) {
            app.icon_url = Uri.fromFile(new File(assetTarget, app.icon_url.substring(7))).toString();
        }
        if (!LuaAppStore.saveLocalApp(context, app)) throw new Exception("本地应用元数据保存失败");
        if (LuaAppStore.find(context, app.id) == null || LuaAppStore.readScript(context, app.id) == null)
            throw new Exception("导入校验失败：应用或脚本未落盘");
        return app;
    }

    private static byte[] unwrapSingleArchive(byte[] archive) throws Exception {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive));
        ZipEntry only = null;
        byte[] content = null;
        int files = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            files++;
            if (files > 1) { zip.close(); return archive; }
            only = entry;
            String lower = entry.getName() == null ? "" : entry.getName().toLowerCase();
            if (lower.contains("/") || (!lower.endsWith(".cip") && !lower.endsWith(".zip"))) {
                zip.close(); return archive;
            }
            content = readLimited(zip, MAX_PACKAGE);
            zip.closeEntry();
        }
        zip.close();
        return files == 1 && only != null && content != null ? content : archive;
    }

    private static byte[] readLimited(InputStream input, int max) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count; int total = 0;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count; if (total > max) throw new Exception("文件超过大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static boolean safePath(String name) {
        return name.length() > 0 && !name.startsWith("/") && !name.contains("../")
                && !name.equals("..") && !name.contains(":") && !name.contains("//");
    }
    private static boolean allowedAsset(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
                || n.endsWith(".gif") || n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".md");
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); }
        file.delete();
    }
}
