package com.im.oldchat.ui;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Locale;

class ChatFileUploadHelper {
    static final int REQ_PICK_FILE = 2005;
    private static final long MAX_FILE_BYTES = 500L * 1024L * 1024L;
    static final String STATE_HASHING = "hashing";
    static final String STATE_CHECKING = "checking";
    static final String STATE_UPLOADING = "uploading";
    static final String STATE_SENDING = "sending";
    static final String STATE_FAILED = "failed";

    interface FileUploadListener {
        void onQueued(String localId, String fileName, long fileSize);
        void onStatus(String localId, String fileName, long fileSize, String state, int percent);
        void onProgress(String localId, String fileName, long fileSize, int percent);
        void onUploaded(String localId, String fileName, long fileSize, String downloadUrl);
        void onFailed(String localId, String fileName, long fileSize, String error);
    }

    private final Activity activity;
    private final ChatMediaHelper.SendStateListener sendStateListener;
    private final FileUploadListener fileUploadListener;

    ChatFileUploadHelper(Activity activity,
                         ChatMediaHelper.SendStateListener sendStateListener,
                         FileUploadListener fileUploadListener) {
        this.activity = activity;
        this.sendStateListener = sendStateListener;
        this.fileUploadListener = fileUploadListener;
    }

    void pickFile() {
        if (activity == null) {
            return;
        }
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        activity.startActivityForResult(android.content.Intent.createChooser(intent, "选择文件"), REQ_PICK_FILE);
    }

    void pickLosslessImage() {
        if (activity == null) {
            return;
        }
        android.content.Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
        } else if (android.os.Build.VERSION.SDK_INT >= 19) {
            intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        }
        intent.setType("image/*");
        activity.startActivityForResult(android.content.Intent.createChooser(intent, "选择无损图片"), REQ_PICK_FILE);
    }

    void pickLosslessVideo() {
        if (activity == null) {
            return;
        }
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        activity.startActivityForResult(android.content.Intent.createChooser(intent, "选择无损视频"), REQ_PICK_FILE);
    }

    void showFileOptionsDialog() {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final String[] options = new String[]{
                "无损图片（以文件形式发送）",
                "无损视频（文件形式发送）",
                "发送文件"
        };
        new android.support.v7.app.AlertDialog.Builder(activity, R.style.AppDialogTheme)
                .setTitle("选择文件发送方式")
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            pickLosslessImage();
                        } else if (which == 1) {
                            pickLosslessVideo();
                        } else if (which == 2) {
                            pickFile();
                        }
                    }
                })
                .show();
    }

    boolean handleActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_PICK_FILE) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        uploadFile(data.getData());
        return true;
    }

    private void uploadFile(final Uri uri) {
        if (activity == null || uri == null) {
            return;
        }
        final String fileName = queryDisplayName(uri);
        final String uploadFileName = buildUploadFileName(fileName);
        final long declaredSize = querySize(uri);
        final String localId = "local_file_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 1000));
        if (declaredSize > MAX_FILE_BYTES) {
            Toast.makeText(activity, "文件不能超过500MB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fileUploadListener != null) {
            fileUploadListener.onQueued(localId, fileName, declaredSize);
        }
        if (sendStateListener != null) {
            sendStateListener.onSendState(true);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final HashResult hashResult = calculateSHA256(uri, declaredSize, localId, fileName);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (hashResult.error != null) {
                            finishWithError(localId, fileName, hashResult.sizeBytes, hashResult.error);
                            return;
                        }
                        checkDuplicateThenUpload(uri, localId, fileName, uploadFileName,
                                resolveMime(uri, fileName), hashResult.sizeBytes, hashResult.sha256);
                    }
                });
            }
        }).start();
    }

    private void checkDuplicateThenUpload(final Uri uri, final String localId, final String fileName,
                                          final String uploadFileName, final String mimeType,
                                          final long fileSize, final String sha256) {
        notifyStatus(localId, fileName, fileSize, STATE_CHECKING, -1);
        JSONObject body = new JSONObject();
        try {
            body.put("sha256", sha256);
            body.put("size_bytes", fileSize);
        } catch (Exception e) {
            startMultipartUpload(uri, localId, fileName, uploadFileName, mimeType, fileSize, sha256);
            return;
        }
        String token = ChatFileUploadHttpClient.resolveAccessToken(activity);
        HttpUtil.post("/v2/files/check", body, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response == null ? "{}" : response);
                    if (obj.optBoolean("exists", false)) {
                        String url = ChatFileUploadHttpClient.normalizeDownloadUrl(obj.optString("url"), "");
                        if (url.length() > 0) {
                            notifyStatus(localId, fileName, fileSize, STATE_SENDING, 100);
                            finishSendState();
                            if (fileUploadListener != null) {
                                fileUploadListener.onUploaded(localId, fileName, fileSize, url);
                            }
                            return;
                        }
                    }
                } catch (Exception ignored) {
                }
                startMultipartUpload(uri, localId, fileName, uploadFileName, mimeType, fileSize, sha256);
            }

            @Override
            public void onError(int code, String error) {
                // 查重服务临时不可用时继续上传；服务端仍会计算哈希并去重。
                startMultipartUpload(uri, localId, fileName, uploadFileName, mimeType, fileSize, sha256);
            }
        });
    }

    private void startMultipartUpload(final Uri uri, final String localId, final String fileName,
                                      final String uploadFileName, final String mimeType,
                                      final long fileSize, final String sha256) {
        notifyStatus(localId, fileName, fileSize, STATE_UPLOADING, 0);
        final String apiBase = ChatFileUploadHttpClient.resolveFilesApiBaseUrl(activity);
        final String uploadUrl = ChatFileUploadHttpClient.buildUploadUrl(apiBase);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ChatFileUploadHttpClient.UploadResult result = ChatFileUploadHttpClient.upload(
                        activity, uploadUrl, uri, uploadFileName, mimeType, fileSize, sha256,
                        new ChatFileUploadHttpClient.ProgressListener() {
                            @Override
                            public void onProgress(final int percent) {
                                activity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (fileUploadListener != null) {
                                            fileUploadListener.onProgress(localId, fileName, fileSize, percent);
                                        }
                                    }
                                });
                            }
                        });
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishSendState();
                        handleUploadResult(localId, fileName, fileSize, result, apiBase);
                    }
                });
            }
        }).start();
    }

    private HashResult calculateSHA256(Uri uri, long declaredSize, String localId, String fileName) {
        InputStream input = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input = activity.getContentResolver().openInputStream(uri);
            if (input == null) {
                return HashResult.error("无法读取文件", 0);
            }
            byte[] buffer = new byte[32 * 1024];
            long readBytes = 0;
            int lastPercent = -1;
            int len;
            while ((len = input.read(buffer)) != -1) {
                if (readBytes > MAX_FILE_BYTES - len) {
                    return HashResult.error("文件不能超过500MB", readBytes);
                }
                digest.update(buffer, 0, len);
                readBytes += len;
                if (declaredSize > 0) {
                    int percent = (int) Math.min(100L, (readBytes * 100L) / declaredSize);
                    if (percent >= 100 || percent >= lastPercent + 2) {
                        lastPercent = percent;
                        notifyStatus(localId, fileName, declaredSize, STATE_HASHING, percent);
                    }
                }
            }
            if (readBytes <= 0) {
                return HashResult.error("文件为空", readBytes);
            }
            return new HashResult(toHex(digest.digest()), readBytes, null);
        } catch (Exception e) {
            return HashResult.error("无法读取文件", 0);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void notifyStatus(final String localId, final String fileName, final long fileSize,
                              final String state, final int percent) {
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (fileUploadListener != null) {
                    fileUploadListener.onStatus(localId, fileName, fileSize, state, percent);
                }
            }
        });
    }

    private void finishWithError(String localId, String fileName, long fileSize, String error) {
        finishSendState();
        if (fileUploadListener != null) {
            fileUploadListener.onFailed(localId, fileName, fileSize, error);
        }
        Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
    }

    private void finishSendState() {
        if (sendStateListener != null) {
            sendStateListener.onSendState(false);
        }
    }

    private static String toHex(byte[] data) {
        char[] out = new char[data.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xff;
            out[i * 2] = digits[value >>> 4];
            out[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(out);
    }

    private static class HashResult {
        final String sha256;
        final long sizeBytes;
        final String error;

        HashResult(String sha256, long sizeBytes, String error) {
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.error = error;
        }

        static HashResult error(String error, long sizeBytes) {
            return new HashResult("", sizeBytes, error);
        }
    }

    private void handleUploadResult(String localId, String fileName, long fileSize,
                                    ChatFileUploadHttpClient.UploadResult result, String apiBase) {
        if (result == null) {
            if (fileUploadListener != null) {
                fileUploadListener.onFailed(localId, fileName, fileSize, "发送文件失败");
            }
            Toast.makeText(activity, "发送文件失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (result.code >= 200 && result.code < 300) {
            try {
                JSONObject obj = new JSONObject(result.body == null ? "{}" : result.body);
                String url = obj.optString("url");
                if (url == null || url.length() == 0) {
                    url = obj.optString("download_path");
                }
                url = ChatFileUploadHttpClient.normalizeDownloadUrl(url, apiBase);
                if (url == null || url.length() == 0) {
                    if (fileUploadListener != null) {
                        fileUploadListener.onFailed(localId, fileName, fileSize, "文件上传响应无下载链接");
                    }
                    Toast.makeText(activity, "文件上传响应无下载链接", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (fileUploadListener != null) {
                    fileUploadListener.onUploaded(localId, fileName, fileSize, url);
                }
                return;
            } catch (Exception ignored) {
                if (fileUploadListener != null) {
                    fileUploadListener.onFailed(localId, fileName, fileSize, "发送文件失败");
                }
                Toast.makeText(activity, "发送文件失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String body = result.body == null ? "" : result.body;
        if (result.code == 413 || body.contains("file_too_large")) {
            if (fileUploadListener != null) {
                fileUploadListener.onFailed(localId, fileName, fileSize, "文件不能超过500MB");
            }
            Toast.makeText(activity, "文件不能超过500MB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (fileUploadListener != null) {
            fileUploadListener.onFailed(localId, fileName, fileSize, "发送文件失败: " + result.code);
        }
        Toast.makeText(activity, "发送文件失败: " + result.code, Toast.LENGTH_SHORT).show();
    }

    private String resolveMime(Uri uri, String fileName) {
        String mime = activity.getContentResolver().getType(uri);
        if (mime != null && mime.length() > 0) {
            return mime;
        }
        String ext = "";
        if (fileName != null) {
            int index = fileName.lastIndexOf('.');
            if (index >= 0 && index + 1 < fileName.length()) {
                ext = fileName.substring(index + 1).toLowerCase();
            }
        }
        if (ext.length() > 0) {
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (guess != null && guess.length() > 0) {
                return guess;
            }
        }
        return "application/octet-stream";
    }

    private String buildUploadFileName(String originalName) {
        String safeName = originalName == null ? "" : originalName.trim();
        if (safeName.length() == 0) {
            safeName = "file";
        }
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR) % 100;
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        String timeSuffix = String.format(Locale.US, "%02d%d%02d%02d%02d", year, month, day, hour, minute);

        int dot = safeName.lastIndexOf('.');
        if (dot > 0 && dot < safeName.length() - 1) {
            String base = safeName.substring(0, dot);
            String ext = safeName.substring(dot);
            return base + "-" + timeSuffix + ext;
        }
        return safeName + "-" + timeSuffix;
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && name.trim().length() > 0) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 0) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < path.length()) {
                path = path.substring(slash + 1);
            }
            if (path.length() > 0) {
                return path;
            }
        }
        return "file_" + System.currentTimeMillis();
    }

    private long querySize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    long size = cursor.getLong(idx);
                    if (size > 0) {
                        return size;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }
}
