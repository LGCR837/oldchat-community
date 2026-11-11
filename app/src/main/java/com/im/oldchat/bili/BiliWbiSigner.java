package com.im.oldchat.bili;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class BiliWbiSigner {
    private static final int[] MIXIN_TABLE = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private BiliWbiSigner() {
    }

    public static SignResult sign(Map<String, String> params, String imgKey, String subKey) {
        if (params == null || imgKey == null || subKey == null) {
            return null;
        }
        String mixinKey = getMixinKey(imgKey + subKey);
        long wts = System.currentTimeMillis() / 1000L;
        params.put("wts", String.valueOf(wts));
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.get(key);
            if (value == null) {
                value = "";
            }
            value = sanitize(value);
            if (query.length() > 0) {
                query.append("&");
            }
            query.append(key).append("=").append(encode(value));
        }
        String wRid = md5(query.toString() + mixinKey);
        SignResult result = new SignResult();
        result.wRid = wRid;
        result.wts = wts;
        return result;
    }

    private static String getMixinKey(String orig) {
        if (orig == null || orig.length() < 64) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append(orig.charAt(MIXIN_TABLE[i]));
        }
        return sb.toString();
    }

    private static String sanitize(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String encode(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%');
                String hex = Integer.toHexString(c).toUpperCase();
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
        }
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(bytes[i] & 0xFF);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static class SignResult {
        public String wRid;
        public long wts;
    }
}
