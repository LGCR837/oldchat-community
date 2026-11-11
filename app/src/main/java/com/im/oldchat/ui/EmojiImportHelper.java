package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;

final class EmojiImportHelper {
    interface Callback {
        void onComplete(int successCount, int failureCount);
    }

    private EmojiImportHelper() {
    }

    static boolean startPickImages(Activity activity, int requestCode, CharSequence title) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        try {
            activity.startActivityForResult(Intent.createChooser(intent, title), requestCode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void importSelectedEmojis(Activity activity, Intent data, final Callback callback) {
        final ArrayList<Uri> uris = collectUris(data);
        if (uris.isEmpty()) {
            postResult(callback, 0, 0);
            return;
        }
        final Context appContext = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int successCount = 0;
                int failureCount = 0;
                for (Uri uri : uris) {
                    if (EmojiStore.addFromUri(appContext, uri) != null) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                }
                postResult(callback, successCount, failureCount);
            }
        }, "emoji-import").start();
    }

    static String buildImportResultMessage(int successCount, int failureCount) {
        if (successCount > 0 && failureCount <= 0) {
            if (successCount == 1) {
                return "已添加到\u201c未分类\u201d";
            }
            return "已添加 " + successCount + " 个表情到\u201c未分类\u201d";
        }
        if (successCount > 0) {
            return "已添加 " + successCount + " 个表情，" + failureCount + " 个不支持或超过3MB";
        }
        if (failureCount > 0) {
            return "不支持的表情格式或文件超过3MB";
        }
        return null;
    }

    private static ArrayList<Uri> collectUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (data == null) {
            return uris;
        }
        addClipDataUris(data, uris);
        addUri(uris, data.getData());
        return uris;
    }

    private static void addClipDataUris(Intent data, ArrayList<Uri> uris) {
        if (Build.VERSION.SDK_INT < 16 || data == null) {
            return;
        }
        try {
            Method getClipData = Intent.class.getMethod("getClipData");
            Object clipData = getClipData.invoke(data);
            if (clipData == null) {
                return;
            }
            Method getItemCount = clipData.getClass().getMethod("getItemCount");
            int count = ((Integer) getItemCount.invoke(clipData)).intValue();
            Method getItemAt = clipData.getClass().getMethod("getItemAt", Integer.TYPE);
            for (int i = 0; i < count; i++) {
                Object item = getItemAt.invoke(clipData, Integer.valueOf(i));
                if (item == null) {
                    continue;
                }
                Method getUri = item.getClass().getMethod("getUri");
                Object uriValue = getUri.invoke(item);
                if (uriValue instanceof Uri) {
                    addUri(uris, (Uri) uriValue);
                }
            }
        } catch (Exception e) {
        }
    }

    private static void addUri(ArrayList<Uri> uris, Uri uri) {
        if (uri == null) {
            return;
        }
        String target = uri.toString();
        for (Uri existing : uris) {
            if (target.equals(existing.toString())) {
                return;
            }
        }
        uris.add(uri);
    }

    private static void postResult(final Callback callback, final int successCount, final int failureCount) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                callback.onComplete(successCount, failureCount);
            }
        });
    }
}
