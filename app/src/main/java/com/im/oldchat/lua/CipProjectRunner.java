package com.im.oldchat.lua;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public final class CipProjectRunner {
    public static final String PREVIEW_ID = "local_dev_preview";

    private CipProjectRunner() { }

    public static Result validate(CipWorkspace workspace) {
        try {
            String manifestText = workspace.read("manifest.json");
            String script = workspace.read("main.lua");
            JSONObject obj = new JSONObject(manifestText);
            LuaMiniApp app = parseManifest(obj);
            Globals globals = new Globals();
            globals.load(new BaseLib());
            globals.load(new PackageLib());
            globals.load(new Bit32Lib());
            globals.load(new CoroutineLib());
            globals.load(new TableLib());
            globals.load(new StringLib());
            globals.load(new MathLib());
            LoadState.install(globals);
            LuaC.install(globals);
            globals.set("package", LuaValue.NIL);
            globals.set("require", LuaValue.NIL);
            globals.load(script, "@main.lua"); // 只编译，不执行未知脚本。
            if (workspace.listFiles().size() > 128) throw new Exception("项目文件超过 128 个");
            return new Result(true, "Manifest 与 Lua 语法测试通过", app, script);
        } catch (Throwable e) {
            String message = e.getMessage();
            return new Result(false, message == null ? e.getClass().getSimpleName() : message, null, null);
        }
    }

    public static Result installPreview(Context context, CipWorkspace workspace) {
        Result result = validate(workspace);
        if (!result.success) return result;
        try {
            LuaMiniApp preview = result.app;
            preview.id = PREVIEW_ID;
            preview.name = "[预览] " + preview.name;
            preview.enabled = true;
            if (!LuaAppStore.writeScriptAtomic(context, PREVIEW_ID, result.script))
                throw new Exception("预览脚本保存失败");
            File targetAssets = LuaAppStore.localAssetDir(context, PREVIEW_ID);
            delete(targetAssets);
            File sourceAssets = new File(workspace.getRoot(), "assets");
            copyDirectory(sourceAssets, targetAssets);
            if (preview.icon_url != null && preview.icon_url.startsWith("assets/"))
                preview.icon_url = android.net.Uri.fromFile(new File(targetAssets, preview.icon_url.substring(7))).toString();
            if (!LuaAppStore.saveLocalApp(context, preview)) throw new Exception("预览元数据保存失败");
            if (LuaAppStore.find(context, PREVIEW_ID) == null || LuaAppStore.readScript(context, PREVIEW_ID) == null)
                throw new Exception("预览安装校验失败");
            return new Result(true, "预览项目已安装", preview, result.script);
        } catch (Exception e) {
            return new Result(false, e.getMessage(), null, null);
        }
    }

    private static LuaMiniApp parseManifest(JSONObject obj) throws Exception {
        String id = obj.optString("id", "");
        if (!id.matches("[a-z0-9_-]{1,64}")) throw new Exception("manifest.id 不合法");
        String name = obj.optString("name", "").trim();
        if (name.length() == 0 || name.length() > 40) throw new Exception("manifest.name 不合法");
        int version = obj.optInt("version", 0);
        if (version <= 0) throw new Exception("manifest.version 必须为正整数");
        LuaMiniApp app = new LuaMiniApp();
        app.id = id; app.name = name; app.version = version;
        app.description = obj.optString("description", "");
        if (app.description.length() > 200) throw new Exception("description 超过 200 字符");
        app.icon_url = obj.optString("icon_url", "");
        app.order = obj.optInt("order", 0); app.enabled = true;
        JSONArray permissions = obj.optJSONArray("permissions");
        if (permissions != null) for (int i = 0; i < permissions.length(); i++) {
            String value = permissions.optString(i, "");
            if (!"camera".equals(value) && !"network".equals(value) && !"network_external".equals(value) && !"storage".equals(value))
                throw new Exception("不支持的权限：" + value);
            if (!app.permissions.contains(value)) app.permissions.add(value);
        }
        JSONArray hosts = obj.optJSONArray("allowed_hosts");
        if (hosts != null) for (int i = 0; i < hosts.length(); i++) {
            String host = hosts.optString(i, "").trim().toLowerCase(java.util.Locale.US);
            if (!"*".equals(host) && !host.matches("(\\*\\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) throw new Exception("allowed_hosts 域名不合法：" + host);
            if (!app.allowedHosts.contains(host)) app.allowedHosts.add(host);
        }
        return app;
    }

    private static void copyDirectory(File source, File target) throws Exception {
        if (source == null || !source.exists()) { target.mkdirs(); return; }
        if (!target.exists() && !target.mkdirs()) throw new Exception("无法创建预览资源目录");
        File[] files = source.listFiles(); if (files == null) return;
        for (File sourceFile : files) {
            File targetFile = new File(target, sourceFile.getName());
            if (sourceFile.isDirectory()) copyDirectory(sourceFile, targetFile);
            else {
                FileInputStream input = new FileInputStream(sourceFile);
                FileOutputStream output = new FileOutputStream(targetFile);
                try { byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count); }
                finally { input.close(); output.close(); }
            }
        }
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); }
        file.delete();
    }

    public static final class Result {
        public final boolean success; public final String message; public final LuaMiniApp app; public final String script;
        Result(boolean success, String message, LuaMiniApp app, String script) {
            this.success = success; this.message = message == null ? "" : message; this.app = app; this.script = script;
        }
    }
}
