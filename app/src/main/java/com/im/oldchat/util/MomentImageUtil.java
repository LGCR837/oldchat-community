package com.im.oldchat.util;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MomentImageUtil {
    private MomentImageUtil() {
    }

    public static List<String> parseUrls(String raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return Collections.emptyList();
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            try {
                JSONArray arr = new JSONArray(value);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String url = arr.optString(i, "").trim();
                    if (!url.isEmpty()) {
                        out.add(url);
                    }
                }
                return out;
            } catch (Exception ignored) {
            }
        }
        if (value.contains(",")) {
            String[] parts = value.split(",");
            List<String> out = new ArrayList<>();
            for (String part : parts) {
                if (part == null) {
                    continue;
                }
                String url = part.trim();
                if (!url.isEmpty()) {
                    out.add(url);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        List<String> single = new ArrayList<>();
        single.add(value);
        return single;
    }

    public static String encodeUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        if (urls.size() == 1) {
            return urls.get(0);
        }
        JSONArray arr = new JSONArray();
        for (String url : urls) {
            if (url != null && !url.trim().isEmpty()) {
                arr.put(url.trim());
            }
        }
        return arr.toString();
    }
}
