package com.im.oldchat.util;

import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

public final class SiliconFlowAsrClient {
    private static final String ASR_PATH = "/voice/asr";

    public interface Callback {
        void onSuccess(String text);

        void onError(String message);
    }

    private SiliconFlowAsrClient() {
    }

    public static void transcribe(String token, byte[] audioData, String fileName,
                                  String contentType, final Callback callback) {
        if (audioData == null || audioData.length == 0) {
            dispatchError(callback, "empty_audio");
            return;
        }
        String safeFileName = fileName == null || fileName.length() == 0 ? "voice.3gp" : fileName;
        String safeContentType = contentType == null || contentType.length() == 0
                ? "audio/3gpp" : contentType;
        HttpUtil.postMultipart(ASR_PATH, audioData, safeFileName, safeContentType, token,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        try {
                            JSONObject obj = new JSONObject(response == null ? "{}" : response);
                            String text = obj.optString("text", "");
                            if (text != null) {
                                text = text.trim();
                            }
                            if (text == null || text.length() == 0) {
                                dispatchError(callback, "empty_result");
                                return;
                            }
                            if (callback != null) {
                                callback.onSuccess(text);
                            }
                        } catch (Exception e) {
                            dispatchError(callback, "invalid_response");
                        }
                    }

                    @Override
                    public void onError(int code, String error) {
                        String message = parseServerError(code, error);
                        dispatchError(callback, message);
                    }
                });
    }

    private static String parseServerError(int code, String error) {
        if (error != null) {
            try {
                JSONObject obj = new JSONObject(error);
                String msg = obj.optString("error", "");
                if (msg != null) {
                    msg = msg.trim();
                }
                if (msg != null && msg.length() > 0) {
                    return msg;
                }
                String asrCode = obj.optString("code", "");
                if (asrCode != null) {
                    asrCode = asrCode.trim();
                }
                if (asrCode != null && asrCode.length() > 0) {
                    return asrCode;
                }
            } catch (Exception ignored) {
            }
        }
        return "http_" + code;
    }

    private static void dispatchError(Callback callback, String message) {
        if (callback != null) {
            callback.onError(message == null ? "transcribe_failed" : message);
        }
    }
}
