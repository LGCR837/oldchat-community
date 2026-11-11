package com.im.oldchat.lua;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.im.oldchat.util.ServerBaseUrlManager;

public final class VibeConfigStore {
    private static final String PREFS = "cip_vibe_config";
    private VibeConfigStore() { }

    public static Config load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config config = new Config();
        config.managed = prefs.getBoolean("use_oldchat", true);
        if (config.managed) {
            String base = ServerBaseUrlManager.getCurrentBaseUrl();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            config.baseUrl = base + "/ai/chat/completions";
            config.modelId = "deepseek-v4-flash";
            SharedPreferences auth = context.getSharedPreferences("auth", Context.MODE_PRIVATE);
            config.apiKey = auth.getString("access_token", "");
            if (config.apiKey.length() == 0) config.apiKey = auth.getString("token", "");
            return config;
        }
        config.baseUrl = prefs.getString("base_url", "");
        config.modelId = prefs.getString("model_id", "");
        String encoded = prefs.getString("api_key", "");
        try { config.apiKey = new String(Base64.decode(encoded, Base64.NO_WRAP), "UTF-8"); }
        catch (Exception ignored) { config.apiKey = ""; }
        return config;
    }

    public static boolean useOldchat(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("use_oldchat", true).commit();
    }

    public static boolean save(Context context, String baseUrl, String apiKey, String modelId) {
        try {
            String encoded = Base64.encodeToString((apiKey == null ? "" : apiKey).getBytes("UTF-8"), Base64.NO_WRAP);
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("use_oldchat", false)
                    .putString("base_url", baseUrl == null ? "" : baseUrl.trim())
                    .putString("api_key", encoded)
                    .putString("model_id", modelId == null ? "" : modelId.trim()).commit();
        } catch (Exception e) { return false; }
    }

    public static final class Config {
        public String baseUrl = ""; public String apiKey = ""; public String modelId = ""; public boolean managed;
        public boolean isValid() { return baseUrl.startsWith("http") && apiKey.length() > 0 && modelId.length() > 0; }
    }
}
