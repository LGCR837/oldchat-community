package com.im.oldchat.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.util.CacheSizeUtil;
import com.im.oldchat.util.EmojiBackupManager;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.LogManager;
import com.im.oldchat.util.MediaCacheCleaner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DataSettingsActivity extends BaseActivity {
    private static final int REQ_PICK_EMOJI_ZIP = 5201;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_settings);

        View btnBack = (View) findViewByIdCompat(R.id.btnDataSettingsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        View btnCacheSettings = (View) findViewByIdCompat(R.id.btnCacheSettings);
        if (btnCacheSettings != null) {
            btnCacheSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(DataSettingsActivity.this, CacheSettingsActivity.class));
                }
            });
        }

        View btnClearAvatarCache = (View) findViewByIdCompat(R.id.btnClearAvatarCache);
        if (btnClearAvatarCache != null) {
            btnClearAvatarCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ImageLoader.clearDiskCache(DataSettingsActivity.this);
                    Toast.makeText(DataSettingsActivity.this,
                            "\u5DF2\u6E05\u7406\u56FE\u7247\u7F13\u5B58",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnDeepClearMediaCache = (View) findViewByIdCompat(R.id.btnDeepClearMediaCache);
        if (btnDeepClearMediaCache != null) {
            btnDeepClearMediaCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long before = MediaCacheCleaner.calculateDeepMediaCacheBytes(DataSettingsActivity.this);
                    MediaCacheCleaner.clearDeepMediaCache(DataSettingsActivity.this);
                    long after = MediaCacheCleaner.calculateDeepMediaCacheBytes(DataSettingsActivity.this);
                    long released = before - after;
                    if (released < 0) {
                        released = 0;
                    }
                    Toast.makeText(DataSettingsActivity.this,
                            "\u5DF2\u6DF1\u5EA6\u6E05\u7406\u5A92\u4F53\u7F13\u5B58\uFF0C\u91CA\u653E " + CacheSizeUtil.formatSize(released),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnExportEmojis = (View) findViewByIdCompat(R.id.btnExportEmojis);
        if (btnExportEmojis != null) {
            btnExportEmojis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportEmojis();
                }
            });
        }

        View btnImportEmojis = (View) findViewByIdCompat(R.id.btnImportEmojis);
        if (btnImportEmojis != null) {
            btnImportEmojis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    importEmojis();
                }
            });
        }

        View btnExportLogs = (View) findViewByIdCompat(R.id.btnExportLogs);
        if (btnExportLogs != null) {
            btnExportLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportLogs();
                }
            });
        }
    }

    private void exportLogs() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ZipOutputStream zos = null;
                try {
                    File logDir = LogManager.getInstance(DataSettingsActivity.this).getLogDir();
                    File[] logFiles = logDir.listFiles();
                    if (!logDir.exists() || logFiles == null || logFiles.length == 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(DataSettingsActivity.this,
                                        "\u6CA1\u6709\u65E5\u5FD7\u6587\u4EF6",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }

                    String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                            .format(new Date());
                    final File zipFile = new File(getExternalFilesDir(null), "logs_" + timestamp + ".zip");

                    zos = new ZipOutputStream(new FileOutputStream(zipFile));
                    for (int i = 0; i < logFiles.length; i++) {
                        File file = logFiles[i];
                        if (file == null || !file.isFile()) {
                            continue;
                        }
                        ZipEntry entry = new ZipEntry(file.getName());
                        zos.putNextEntry(entry);

                        FileInputStream fis = null;
                        try {
                            fis = new FileInputStream(file);
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        } finally {
                            if (fis != null) {
                                try {
                                    fis.close();
                                } catch (Exception e) {
                                }
                            }
                        }
                        zos.closeEntry();
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(DataSettingsActivity.this,
                                    "\u65E5\u5FD7\u5DF2\u5BFC\u51FA\u5230: " + zipFile.getAbsolutePath(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(DataSettingsActivity.this,
                                    "\u5BFC\u51FA\u65E5\u5FD7\u5931\u8D25",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (zos != null) {
                        try {
                            zos.close();
                        } catch (Exception e) {
                        }
                    }
                }
            }
        }).start();
    }

    private void exportEmojis() {
        Toast.makeText(this, "\u6B63\u5728\u5BFC\u51FA\u8868\u60C5\u5305...", Toast.LENGTH_SHORT).show();
        EmojiBackupManager.exportEmojis(this, new EmojiBackupManager.BackupCallback() {
            @Override
            public void onSuccess(String message, File file) {
                Toast.makeText(DataSettingsActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(DataSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void importEmojis() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent,
                    "\u9009\u62E9\u8868\u60C5\u5305 ZIP \u6587\u4EF6"), REQ_PICK_EMOJI_ZIP);
        } catch (Exception e) {
            Toast.makeText(this, "\u65E0\u6CD5\u6253\u5F00\u6587\u4EF6\u9009\u62E9\u5668", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_EMOJI_ZIP && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Toast.makeText(this, "\u6B63\u5728\u5BFC\u5165\u8868\u60C5\u5305...", Toast.LENGTH_SHORT).show();
                EmojiBackupManager.importEmojis(this, uri, new EmojiBackupManager.RestoreCallback() {
                    @Override
                    public void onSuccess(String message, int count) {
                        Toast.makeText(DataSettingsActivity.this, message, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(DataSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }
}
