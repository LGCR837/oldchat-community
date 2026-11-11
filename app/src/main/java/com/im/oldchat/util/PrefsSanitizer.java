package com.im.oldchat.util;

import android.content.Context;
import android.os.SystemClock;
import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import org.xmlpull.v1.XmlPullParser;

public final class PrefsSanitizer {
    private static final long MAX_VALIDATE_BYTES = 64L * 1024L;
    private static final long STARTUP_BUDGET_MS = 60L;
    private static final int MAX_FILES_PER_START = 24;

    private PrefsSanitizer() {
    }

    public static void sanitize(Context context) {
        if (context == null) {
            return;
        }
        File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long startedAt = SystemClock.uptimeMillis();
        int checked = 0;
        for (int i = 0; i < files.length; i++) {
            if (checked >= MAX_FILES_PER_START
                    || SystemClock.uptimeMillis() - startedAt >= STARTUP_BUDGET_MS) {
                break;
            }
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name == null || !name.endsWith(".xml")) {
                continue;
            }
            if (file.length() == 0) {
                safeDelete(file);
                continue;
            }
            // 消息历史等偏好文件可能达到数 MB；启动时完整 XML 扫描会在 Android 2.3
            // 同时制造文件缓冲、解析器节点和字符串，老账户容易在 Application 阶段 OOM。
            if (file.length() > MAX_VALIDATE_BYTES) {
                continue;
            }
            checked++;
            if (!isWellFormedXml(file)) {
                File broken = new File(file.getParent(), name + ".broken");
                try {
                    file.renameTo(broken);
                } catch (Exception e) {
                }
                safeDelete(file);
            }
        }
    }

    private static boolean isWellFormedXml(File file) {
        FileInputStream in = null;
        try {
            XmlPullParser parser = Xml.newPullParser();
            in = new FileInputStream(file);
            parser.setInput(in, "UTF-8");
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                // just iterate to validate
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private static void safeDelete(File file) {
        try {
            file.delete();
        } catch (Exception e) {
        }
    }
}
