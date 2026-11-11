package com.im.oldchat.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v4.content.FileProvider;
import android.widget.Toast;

import com.im.oldchat.models.FileDownloadRecord;

import java.io.File;

public final class ExternalFileOpener {
    private ExternalFileOpener() { }

    public static void open(Context context, FileDownloadRecord record) {
        if (context == null || record == null) return;
        try {
            Uri uri;
            if (record.savedUri != null && record.savedUri.length() > 0) {
                uri = Uri.parse(record.savedUri);
            } else {
                File file = new File(record.savedPath == null ? "" : record.savedPath);
                if (!file.exists()) {
                    Toast.makeText(context, "文件已不存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
                } else {
                    uri = Uri.fromFile(file);
                }
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, record.mimeType == null ? "application/octet-stream" : record.mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }
}
