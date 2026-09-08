package com.im.oldchat.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.im.oldchat.models.FileDownloadRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class FileDownloadStore {
    private static final String PREFS = "file_downloads";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 100;
    private static final Object LOCK = new Object();

    private FileDownloadStore() { }

    public static List<FileDownloadRecord> list(Context context) {
        synchronized (LOCK) {
            List<FileDownloadRecord> result = read(context);
            Collections.sort(result, new Comparator<FileDownloadRecord>() {
                @Override public int compare(FileDownloadRecord a, FileDownloadRecord b) {
                    if (a.updatedAt == b.updatedAt) return 0;
                    return a.updatedAt > b.updatedAt ? -1 : 1;
                }
            });
            return result;
        }
    }

    public static void save(Context context, FileDownloadRecord record) {
        if (context == null || record == null || record.id == null) return;
        synchronized (LOCK) {
            List<FileDownloadRecord> items = read(context);
            boolean replaced = false;
            for (int i = 0; i < items.size(); i++) {
                if (record.id.equals(items.get(i).id)) {
                    items.set(i, record);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) items.add(record);
            Collections.sort(items, new Comparator<FileDownloadRecord>() {
                @Override public int compare(FileDownloadRecord a, FileDownloadRecord b) {
                    return a.updatedAt == b.updatedAt ? 0 : (a.updatedAt > b.updatedAt ? -1 : 1);
                }
            });
            while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);
            JSONArray array = new JSONArray();
            for (int i = 0; i < items.size(); i++) array.put(items.get(i).toJson());
            prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply();
        }
    }

    public static void delete(Context context, String id) {
        if (context == null || id == null || id.length() == 0) return;
        synchronized (LOCK) {
            List<FileDownloadRecord> items = read(context);
            boolean removed = false;
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(items.get(i).id)) {
                    items.remove(i);
                    removed = true;
                    break;
                }
            }
            if (removed) {
                JSONArray array = new JSONArray();
                for (int i = 0; i < items.size(); i++) array.put(items.get(i).toJson());
                prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply();
            }
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            prefs(context).edit().remove(KEY_ITEMS).apply();
        }
    }

    private static List<FileDownloadRecord> read(Context context) {
        List<FileDownloadRecord> result = new ArrayList<FileDownloadRecord>();
        if (context == null) return result;
        String raw = prefs(context).getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                FileDownloadRecord item = FileDownloadRecord.fromJson(obj);
                if (item != null && item.id != null && item.id.length() > 0) result.add(item);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
