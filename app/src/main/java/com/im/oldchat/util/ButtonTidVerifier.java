package com.im.oldchat.util;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * 消息按钮 tid 离线验签：内置服务端 RSA 公钥，无需联网即可验证 tid 是否由服务端签发。
 * 验证通过才渲染按钮，防伪造/钓鱼。
 */
public final class ButtonTidVerifier {
    // 服务端按钮签名公钥（与 server uploads/keys/button_sign_rsa_pub.pem 一致）
    private static final String PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAukWbBGEr+M7DY+X7zBD8\n" +
            "wZJ/XYCmb4Hg6Vp2S3KALkBosC4vTD5Jf+ZaP0TPjzK1fxxeokHTD3+p9xJckNfo\n" +
            "Ol7szHARDTEvbrmSnua4iLrSGJ83/6yiDrXLNc7LrO7DxnEFkim8VlDMtZBHcVjD\n" +
            "pwAcGq5nwrhL/qN01Fr9/DlhkDmZ3h8IIW068KAcWHHbdBv3EVA/DqfEuQM35n3D\n" +
            "NMM50HsrhQqD2vFqKCBCdXoJhXPcBX55k7YzKh1yYKQm5txZMX4PkWU9bZPT4Lxu\n" +
            "dsnstJBQ7BLMRjKpB7rNw9aHSNMeSjwTigekoSLNbFLEImeALEPVvhSbr0mXX9Vq\n" +
            "PQIDAQAB\n" +
            "-----END PUBLIC KEY-----";

    private static volatile PublicKey cachedKey;

    private ButtonTidVerifier() {
    }

    /** 验证按钮 tid 是否有效。messageId 是按钮所属消息的 ID（服务端签名 payload 用）。 */
    public static boolean verify(MessagePayload.Button button, String messageId) {
        if (button == null || button.tid == null || button.tid.isEmpty()
                || button.action == null || button.nonce == null || button.nonce.isEmpty()) {
            return false;
        }
        try {
            PublicKey key = getPublicKey();
            if (key == null) {
                return false;
            }
            // 与服务端 SignTID 一致：payload = msgID|index|action|nonce。
            String payload = (messageId == null ? "" : messageId) + "|" + button.index
                    + "|" + button.action + "|" + button.nonce;
            byte[] signature = android.util.Base64.decode(button.tid, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(payload.getBytes("UTF-8"));
            return sig.verify(signature);
        } catch (Throwable t) {
            return false;
        }
    }

    private static PublicKey getPublicKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        try {
            String pem = PUBLIC_KEY_PEM
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = android.util.Base64.decode(pem, android.util.Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            cachedKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Throwable t) {
            cachedKey = null;
        }
        return cachedKey;
    }
}
