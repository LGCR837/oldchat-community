package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.im.oldchat.OldChatApplication;

/**
 * ECDH 加密会话持久化存储。
 * 解决 Android 2.3 小内存机型切后台被回收后每次都要重新网络握手的问题。
 */
public class CryptoSessionStore {
    private static final String PREF_NAME = "crypto_session_prefs";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_ENC_KEY = "enc_key_b64";
    private static final String KEY_MAC_KEY = "mac_key_b64";
    private static final String KEY_SAVED_AT = "saved_at";

    private static final Object LOCK = new Object();
    private static String inMemSessionId;
    private static byte[] inMemEncKey;
    private static byte[] inMemMacKey;
    private static boolean loadedFromDisk = false;

    public static class SessionData {
        public final String sessionId;
        public final byte[] encKey;
        public final byte[] macKey;

        public SessionData(String sessionId, byte[] encKey, byte[] macKey) {
            this.sessionId = sessionId;
            this.encKey = encKey;
            this.macKey = macKey;
        }
    }

    public static void setSession(String newSessionId, byte[] encKey, byte[] macKey) {
        synchronized (LOCK) {
            inMemSessionId = newSessionId;
            inMemEncKey = encKey != null ? encKey.clone() : null;
            inMemMacKey = macKey != null ? macKey.clone() : null;
            loadedFromDisk = true;
            persistToDiskLocked(newSessionId, inMemEncKey, inMemMacKey);
        }
    }

    public static void clearSession() {
        synchronized (LOCK) {
            inMemSessionId = null;
            inMemEncKey = null;
            inMemMacKey = null;
            loadedFromDisk = true;
            clearFromDiskLocked();
        }
    }

    public static boolean hasSession() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            return inMemSessionId != null && inMemEncKey != null && inMemMacKey != null;
        }
    }

    public static String getSessionId() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            return inMemSessionId;
        }
    }

    public static SessionData getSessionData() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            if (inMemSessionId == null || inMemEncKey == null || inMemMacKey == null) {
                return null;
            }
            return new SessionData(inMemSessionId, inMemEncKey.clone(), inMemMacKey.clone());
        }
    }

    private static void ensureLoadedLocked() {
        if (loadedFromDisk) {
            return;
        }
        loadedFromDisk = true;
        Context context = OldChatApplication.getAppContext();
        if (context == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String sid = prefs.getString(KEY_SESSION_ID, null);
            String encB64 = prefs.getString(KEY_ENC_KEY, null);
            String macB64 = prefs.getString(KEY_MAC_KEY, null);
            if (sid != null && encB64 != null && macB64 != null) {
                byte[] enc = Base64.decode(encB64, Base64.NO_WRAP);
                byte[] mac = Base64.decode(macB64, Base64.NO_WRAP);
                if (enc != null && mac != null && enc.length == 32 && mac.length == 32) {
                    inMemSessionId = sid;
                    inMemEncKey = enc;
                    inMemMacKey = mac;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persistToDiskLocked(String sid, byte[] encKey, byte[] macKey) {
        Context context = OldChatApplication.getAppContext();
        if (context == null) {
            return;
        }
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            if (sid != null && encKey != null && macKey != null) {
                editor.putString(KEY_SESSION_ID, sid);
                editor.putString(KEY_ENC_KEY, Base64.encodeToString(encKey, Base64.NO_WRAP));
                editor.putString(KEY_MAC_KEY, Base64.encodeToString(macKey, Base64.NO_WRAP));
                editor.putLong(KEY_SAVED_AT, System.currentTimeMillis());
            } else {
                editor.remove(KEY_SESSION_ID);
                editor.remove(KEY_ENC_KEY);
                editor.remove(KEY_MAC_KEY);
                editor.remove(KEY_SAVED_AT);
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    private static void clearFromDiskLocked() {
        Context context = OldChatApplication.getAppContext();
        if (context == null) {
            return;
        }
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            editor.clear();
            editor.apply();
        } catch (Throwable ignored) {
        }
    }
}
