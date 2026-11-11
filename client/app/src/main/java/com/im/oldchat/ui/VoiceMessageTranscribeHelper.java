package com.im.oldchat.ui;

import com.im.oldchat.api.HttpUtil;

import org.json.JSONObject;

final class VoiceMessageTranscribeHelper {
    interface Callback {
        void onSuccess(String text, boolean cached);

        void onError(String message);
    }

    private VoiceMessageTranscribeHelper() {
    }

    static void transcribeDirect(String token, String messageID, Callback callback) {
        request(token, buildPath("/direct/messages/", messageID), callback);
    }

    static void transcribeGroup(String token, String messageID, Callback callback) {
        request(token, buildPath("/groups/messages/", messageID), callback);
    }

    private static String buildPath(String prefix, String messageID) {
        String id = messageID == null ? "" : messageID.trim();
        if (id.length() == 0) {
            return "";
        }
        return prefix + id + "/transcribe";
    }

    private static void request(String token, String path, final Callback callback) {
        if (path == null || path.length() == 0) {
            dispatchError(callback, "invalid_message_id");
            return;
        }
        HttpUtil.post(path, new JSONObject(), token, new HttpUtil.Callback() {
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
                    boolean cached = obj.optBoolean("cached", false);
                    if (callback != null) {
                        callback.onSuccess(text, cached);
                    }
                } catch (Exception e) {
                    dispatchError(callback, "invalid_response");
                }
            }

            @Override
            public void onError(int code, String error) {
                dispatchError(callback, parseServerError(code, error));
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
