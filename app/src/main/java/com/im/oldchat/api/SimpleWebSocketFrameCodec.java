package com.im.oldchat.api;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * WebSocket 帧打包与握手协议编解码工具。
 */
class SimpleWebSocketFrameCodec {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static SecureRandom random;

    public static synchronized SecureRandom getRandom() {
        if (random == null) {
            random = new SecureRandom();
        }
        return random;
    }

    public static String computeAccept(String key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update((key + WS_GUID).getBytes("UTF-8"));
        byte[] sha1 = md.digest();
        return Base64.encodeToString(sha1, Base64.NO_WRAP);
    }

    public static byte[] buildFrame(int opcode, byte[] payload) throws Exception {
        int length = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | (opcode & 0x0F));
        if (length <= 125) {
            frame.write(0x80 | length);
        } else if (length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long l = length;
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((l >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        getRandom().nextBytes(mask);
        frame.write(mask);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        frame.write(masked);
        return frame.toByteArray();
    }
}
