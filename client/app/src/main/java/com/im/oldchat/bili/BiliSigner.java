package com.im.oldchat.bili;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class BiliSigner {
    public static final String TV_APP_KEY = "4409e2ce8ffd12b8";
    public static final String TV_APP_SEC = "59b43e04ad6965f34319062b478f83dd";

    private BiliSigner() {
    }

    public static String sign(Map<String, String> params) {
        return sign(params, TV_APP_KEY, TV_APP_SEC);
    }

    public static String sign(Map<String, String> params, String appKey, String appSec) {
        if (params == null) {
            return "";
        }
        params.put("appkey", appKey);
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.get(key);
            if (value == null) {
                value = "";
            }
            if (i > 0) {
                query.append('&');
            }
            query.append(urlEncode(key)).append('=').append(urlEncode(value));
        }
        return md5(query.toString() + appSec);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
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
}
