package com.im.oldchat.util;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import com.im.oldchat.ui.EmojiStore;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class EmojiBackupManager {

    public interface BackupCallback {
        void onSuccess(String message, File file);
        void onError(String message);
    }

    public interface RestoreCallback {
        void onSuccess(String message, int count);
        void onError(String message);
    }

    public static void exportEmojis(final Context context, final BackupCallback callback) {
        new AsyncTask<Void, Void, File>() {
            private String errorMsg = null;

            @Override
            protected File doInBackground(Void... voids) {
                try {
                    List<EmojiStore.EmojiItem> items = EmojiStore.load(context);
                    if (items == null || items.isEmpty()) {
                        errorMsg = "没有表情包可以导出";
                        return null;
                    }

                    String fileName = "emojis_backup_" + System.currentTimeMillis() + ".zip";
                    File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File zipFile = buildBackupZip(items, exportDir, fileName);
                    if (zipFile != null) {
                        return zipFile;
                    }
                    zipFile = buildBackupZip(items, context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                    if (zipFile != null) {
                        return zipFile;
                    }
                    zipFile = buildBackupZip(items, new File(context.getFilesDir(), "exports"), fileName);
                    if (zipFile != null) {
                        return zipFile;
                    }
                    errorMsg = "导出失败: 无法写入存储目录";
                    return null;
                } catch (Exception e) {
                    errorMsg = "导出失败: " + e.getMessage();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(File file) {
                if (callback == null) {
                    return;
                }
                if (file != null && file.exists()) {
                    callback.onSuccess("表情包已导出到: " + file.getAbsolutePath(), file);
                } else {
                    callback.onError(errorMsg != null ? errorMsg : "导出失败");
                }
            }
        }.execute();
    }

    private static File buildBackupZip(List<EmojiStore.EmojiItem> items, File exportDir, String fileName) {
        if (items == null || items.isEmpty() || exportDir == null || fileName == null || fileName.length() == 0) {
            return null;
        }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            return null;
        }
        File zipFile = new File(exportDir, fileName);
        ZipOutputStream zos = null;
        int successCount = 0;
        try {
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
            for (EmojiStore.EmojiItem item : items) {
                if (item == null || item.path == null || item.path.isEmpty()) {
                    continue;
                }
                File file = new File(item.path);
                if (!file.exists() || !file.isFile()) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(file.getName());
                zos.putNextEntry(entry);
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(file);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, len);
                    }
                    successCount++;
                } finally {
                    if (fis != null) {
                        try { fis.close(); } catch (Exception e) {}
                    }
                }
                zos.closeEntry();
            }
            if (successCount <= 0) {
                if (zipFile.exists()) {
                    zipFile.delete();
                }
                return null;
            }
            return zipFile;
        } catch (Exception e) {
            if (zipFile.exists()) {
                zipFile.delete();
            }
            return null;
        } finally {
            if (zos != null) {
                try { zos.close(); } catch (Exception e) {}
            }
        }
    }

    public static void importEmojis(final Context context, final Uri zipUri, final RestoreCallback callback) {
        new AsyncTask<Void, Void, Integer>() {
            private String errorMsg = null;

            @Override
            protected Integer doInBackground(Void... voids) {
                InputStream is = null;
                ZipInputStream zis = null;
                try {
                    is = context.getContentResolver().openInputStream(zipUri);
                    if (is == null) {
                        errorMsg = "无法读取文件";
                        return 0;
                    }

                    File emojiDir = new File(context.getFilesDir(), "emojis");
                    if (!emojiDir.exists()) {
                        emojiDir.mkdirs();
                    }

                    zis = new ZipInputStream(new BufferedInputStream(is));
                    ZipEntry entry;
                    int importCount = 0;

                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }

                        String name = entry.getName();
                        if (name.contains("/")) {
                            name = name.substring(name.lastIndexOf('/') + 1);
                        }

                        String ext = getExtension(name);
                        if (ext == null) {
                            continue;
                        }

                        String newName = "emoji_" + System.currentTimeMillis() + "_" + importCount + ext;
                        File outFile = new File(emojiDir, newName);

                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(outFile);
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, len);
                            }
                            fos.flush();

                            boolean isGif = ".gif".equalsIgnoreCase(ext);
                            EmojiStore.EmojiItem item = new EmojiStore.EmojiItem();
                            item.id = String.valueOf(System.currentTimeMillis() + importCount);
                            item.path = outFile.getAbsolutePath();
                            item.isGif = isGif;
                            item.category = EmojiStore.DEFAULT_CATEGORY;

                            List<EmojiStore.EmojiItem> list = EmojiStore.load(context);
                            list.add(item);
                            EmojiStore.save(context, list);

                            importCount++;
                        } finally {
                            if (fos != null) {
                                try { fos.close(); } catch (Exception e) {}
                            }
                        }
                        zis.closeEntry();
                    }

                    return importCount;
                } catch (Exception e) {
                    errorMsg = "导入失败: " + e.getMessage();
                    return 0;
                } finally {
                    if (zis != null) {
                        try { zis.close(); } catch (Exception e) {}
                    }
                    if (is != null) {
                        try { is.close(); } catch (Exception e) {}
                    }
                }
            }

            @Override
            protected void onPostExecute(Integer count) {
                if (callback == null) {
                    return;
                }
                if (count > 0) {
                    callback.onSuccess("成功导入 " + count + " 个表情包", count);
                } else {
                    callback.onError(errorMsg != null ? errorMsg : "导入失败");
                }
            }
        }.execute();
    }

    private static String getExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx >= fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(idx).toLowerCase();
        if (".jpg".equals(ext) || ".jpeg".equals(ext) || ".png".equals(ext) || ".gif".equals(ext)) {
            return ext;
        }
        return null;
    }
}
