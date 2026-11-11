package com.oldchat.core.platform;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/** 基于 Properties 文件的 KVStore 实现（桌面端）。 */
public class FileKVStore implements KVStore {
    private final Properties p = new Properties();
    private final File file;

    public FileKVStore(File file) {
        this.file = file;
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public String get(String key, String def) {
        return p.getProperty(key, def);
    }

    @Override
    public void put(String key, String value) {
        p.setProperty(key, value);
        flush();
    }

    @Override
    public void remove(String key) {
        p.remove(key);
        flush();
    }

    @Override
    public void flush() {
        try (OutputStream out = new FileOutputStream(file)) {
            p.store(out, "oldchat");
        } catch (IOException ignored) {
        }
    }
}
