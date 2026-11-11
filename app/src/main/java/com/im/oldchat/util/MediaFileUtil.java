package com.im.oldchat.util;

import java.io.File;

public class MediaFileUtil {
    private MediaFileUtil() {
    }

    public static byte[] readAllBytes(File file) throws Exception {
        java.io.InputStream is = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        is.close();
        return baos.toByteArray();
    }

    public static String resolveImageType(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }
}
