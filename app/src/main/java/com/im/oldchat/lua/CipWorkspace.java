package com.im.oldchat.lua;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CipWorkspace {
    private static final int MAX_TEXT_FILE = 512 * 1024;
    private final File root;

    public CipWorkspace(Context context) {
        root = new File(context.getFilesDir(), "cip_workspace/default");
    }

    public void initialize() throws Exception {
        if (!root.exists() && !root.mkdirs()) throw new Exception("无法创建项目目录");
        File assets = new File(root, "assets");
        if (!assets.exists()) assets.mkdirs();
        File manifest = new File(root, "manifest.json");
        if (!manifest.exists()) write("manifest.json", "{\n  \"id\": \"my_app\",\n  \"name\": \"我的 CIP 应用\",\n  \"description\": \"使用 Oldchat CIP 开发器创建\",\n  \"version\": 1,\n  \"icon_url\": \"\",\n  \"enabled\": true,\n  \"order\": 100,\n  \"permissions\": [\"storage\"]\n}\n");
        File main = new File(root, "main.lua");
        if (!main.exists()) write("main.lua", "local count = tonumber(app.storage_get(\"count\") or \"0\")\n\nreturn ui.page({\n  title = \"我的 CIP 应用\",\n  children = {\n    ui.text({ id = \"status\", text = \"欢迎使用 CIP 开发器\", size = 18, color = \"#6FACC9\" }),\n    ui.text({ text = \"当前点击次数：\" .. tostring(count), margin = 8 }),\n    ui.button({\n      text = \"测试按钮\",\n      on_click = function()\n        count = count + 1\n        app.storage_set(\"count\", tostring(count))\n        app.set_text(\"status\", \"运行正常，次数：\" .. tostring(count))\n      end\n    })\n  }\n})\n");
    }

    public List<String> listFiles() throws Exception {
        initialize();
        ArrayList<String> result = new ArrayList<String>();
        collect(root, "", result);
        Collections.sort(result);
        // 入口文件固定显示在前面。
        result.remove("manifest.json"); result.remove("main.lua");
        result.add(0, "main.lua"); result.add(0, "manifest.json");
        return result;
    }

    private void collect(File dir, String prefix, List<String> out) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String relative = prefix.length() == 0 ? file.getName() : prefix + "/" + file.getName();
            if (file.isDirectory()) collect(file, relative, out);
            else if (isAllowed(relative)) out.add(relative);
        }
    }

    public String read(String relative) throws Exception {
        File file = resolve(relative);
        if (!file.isFile() || file.length() > MAX_TEXT_FILE) throw new Exception("文件不存在或过大");
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            return new String(output.toByteArray(), "UTF-8");
        } finally { input.close(); }
    }

    public void write(String relative, String content) throws Exception {
        if (!isAllowed(relative)) throw new Exception("只允许入口文件和 assets 文本资源");
        byte[] data = (content == null ? "" : content).getBytes("UTF-8");
        if (data.length > MAX_TEXT_FILE) throw new Exception("文件超过 512 KiB");
        File file = resolve(relative);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new Exception("无法创建目录");
        File temp = new File(parent, file.getName() + ".tmp");
        FileOutputStream output = new FileOutputStream(temp, false);
        try { output.write(data); output.flush(); try { output.getFD().sync(); } catch (Exception ignored) { } }
        finally { output.close(); }
        if (file.exists() && !file.delete()) throw new Exception("无法替换文件");
        if (!temp.renameTo(file)) throw new Exception("无法保存文件");
    }

    public void delete(String relative) throws Exception {
        if ("manifest.json".equals(relative) || "main.lua".equals(relative)) throw new Exception("不能删除入口文件");
        File file = resolve(relative);
        if (file.exists() && !file.delete()) throw new Exception("删除失败");
    }

    public File buildCip(Context context) throws Exception {
        initialize();
        File outputFile = new File(context.getCacheDir(), "oldchat-cip-project.zip");
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outputFile, false));
        try {
            List<String> files = listFiles();
            for (String relative : files) {
                byte[] data = read(relative).getBytes("UTF-8");
                zip.putNextEntry(new ZipEntry(relative));
                zip.write(data);
                zip.closeEntry();
            }
        } finally { zip.close(); }
        return outputFile;
    }

    public File getRoot() { return root; }

    private File resolve(String relative) throws Exception {
        if (!isAllowed(relative)) throw new Exception("文件路径不合法");
        File file = new File(root, relative);
        String base = root.getCanonicalPath();
        if (!file.getCanonicalPath().startsWith(base + File.separator)) throw new Exception("文件路径越界");
        return file;
    }

    public static boolean isAllowed(String relative) {
        if (relative == null || relative.length() == 0 || relative.startsWith("/") || relative.contains("..") || relative.contains("\\") || relative.contains(":")) return false;
        if ("manifest.json".equals(relative) || "main.lua".equals(relative)) return true;
        if (!relative.startsWith("assets/")) return false;
        String lower = relative.toLowerCase();
        return lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".md");
    }
}
