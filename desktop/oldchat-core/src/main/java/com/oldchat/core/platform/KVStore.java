package com.oldchat.core.platform;

/** 平台无关的键值存储抽象（替代 Android SharedPreferences）。 */
public interface KVStore {
    String get(String key, String def);

    void put(String key, String value);

    void remove(String key);

    void flush();
}
