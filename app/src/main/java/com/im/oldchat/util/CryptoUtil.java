package com.im.oldchat.util;

import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.zip.GZIPInputStream;
import org.spongycastle.jce.provider.BouncyCastleProvider;

public class CryptoUtil {
    private static final int SECRET_SIZE = 32;
    private static Boolean ecdhSupported;
    private static final Object PROVIDER_LOCK = new Object();
    private static volatile boolean providerReady;

    public static class SessionKeys {
        public final byte[] encKey;
        public final byte[] macKey;

        public SessionKeys(byte[] encKey, byte[] macKey) {
            this.encKey = encKey;
            this.macKey = macKey;
        }
    }

    public static class Handshake {
        private final PrivateKey privateKey;
        private final String publicKey;

        Handshake(PrivateKey privateKey, String publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public SessionKeys finish(String serverPublicKey) throws Exception {
            byte[] serverBytes = Base64.decode(serverPublicKey, Base64.NO_WRAP);
            KeyFactory factory = getEcKeyFactory();
            PublicKey serverKey = factory.generatePublic(new X509EncodedKeySpec(serverBytes));
            KeyAgreement agreement = getEcdhAgreement();
            agreement.init(privateKey);
            agreement.doPhase(serverKey, true);
            byte[] secret = agreement.generateSecret();
            secret = normalizeSecret(secret, SECRET_SIZE);
            byte[] encKey = sha256(concat(secret, "enc".getBytes("UTF-8")));
            byte[] macKey = sha256(concat(secret, "mac".getBytes("UTF-8")));
            return new SessionKeys(encKey, macKey);
        }
    }

    public static Handshake beginHandshake() throws Exception {
        KeyPairGenerator generator = getEcKeyPairGenerator();
        try {
            generator.initialize(new ECGenParameterSpec("secp256r1"));
        } catch (Exception e) {
            generator.initialize(new ECGenParameterSpec("prime256v1"));
        }
        KeyPair keyPair = generator.generateKeyPair();
        String pub = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
        return new Handshake(keyPair.getPrivate(), pub);
    }

    public static void setSession(String newSessionId, byte[] encKey, byte[] macKey) {
        CryptoSessionStore.setSession(newSessionId, encKey, macKey);
    }

    public static void clearSession() {
        CryptoSessionStore.clearSession();
    }

    public static boolean hasSession() {
        return CryptoSessionStore.hasSession();
    }

    public static String getSessionId() {
        return CryptoSessionStore.getSessionId();
    }

    public static String signRequest(String method, String path, String ts, String nonce) {
        CryptoSessionStore.SessionData data = CryptoSessionStore.getSessionData();
        if (data == null || data.macKey == null) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append('\n')
                    .append(path).append('\n')
                    .append(ts).append('\n')
                    .append(nonce);
            byte[] sig = hmac(data.macKey, sb.toString().getBytes("UTF-8"));
            return Base64.encodeToString(sig, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            return null;
        }
    }

    public static String randomNonce() {
        byte[] raw = new byte[16];
        try {
            new SecureRandom().nextBytes(raw);
        } catch (Exception ignored) {
            long t = System.currentTimeMillis();
            raw[0] = (byte) (t >>> 24);
            raw[1] = (byte) (t >>> 16);
            raw[2] = (byte) (t >>> 8);
            raw[3] = (byte) t;
            raw[4] = (byte) (System.nanoTime() & 0xFF);
        }
        return Base64.encodeToString(raw, Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static boolean isEcdhSupported() {
        if (ecdhSupported != null) {
            return ecdhSupported.booleanValue();
        }
        boolean supported = false;
        try {
            KeyPairGenerator generator = getEcKeyPairGenerator();
            try {
                generator.initialize(new ECGenParameterSpec("secp256r1"));
            } catch (Exception e) {
                generator.initialize(new ECGenParameterSpec("prime256v1"));
            }
            getEcdhAgreement();
            supported = true;
        } catch (Exception e) {
            supported = false;
        }
        ecdhSupported = Boolean.valueOf(supported);
        return supported;
    }

    public static void ensureProvider() {
        if (providerReady) {
            return;
        }
        synchronized (PROVIDER_LOCK) {
            if (providerReady) {
                return;
            }
            try {
                if (Security.getProvider("SC") == null) {
                    Security.insertProviderAt(new BouncyCastleProvider(), 1);
                }
            } catch (Throwable ignored) {
            }
            providerReady = true;
        }
    }

    private static KeyPairGenerator getEcKeyPairGenerator() throws Exception {
        ensureProvider();
        try {
            return KeyPairGenerator.getInstance("EC", "SC");
        } catch (Exception scEx) {
            return KeyPairGenerator.getInstance("EC");
        }
    }

    private static KeyAgreement getEcdhAgreement() throws Exception {
        ensureProvider();
        try {
            return KeyAgreement.getInstance("ECDH", "SC");
        } catch (Exception e) {
            return KeyAgreement.getInstance("ECDH");
        }
    }

    private static KeyFactory getEcKeyFactory() throws Exception {
        ensureProvider();
        try {
            return KeyFactory.getInstance("EC", "SC");
        } catch (Exception e) {
            return KeyFactory.getInstance("EC");
        }
    }

    public static String encrypt(String plain) throws Exception {
        CryptoSessionStore.SessionData data = CryptoSessionStore.getSessionData();
        if (data == null || data.encKey == null || data.macKey == null) {
            throw new IllegalStateException("missing session keys");
        }
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(data.encKey, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes("UTF-8"));

        byte[] mac = hmac(data.macKey, concat(iv, encrypted));
        JSONObject obj = new JSONObject();
        obj.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        obj.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        obj.put("mac", Base64.encodeToString(mac, Base64.NO_WRAP));
        return obj.toString();
    }

    public static String decryptIfNeeded(String payload) {
        return decryptIfNeeded(payload, false);
    }

    public static String decryptIfNeeded(String payload, boolean gzipCompressed) {
        try {
            JSONObject obj = new JSONObject(payload);
            if (!obj.has("iv") || !obj.has("data") || !obj.has("mac")) {
                return null;
            }
            CryptoSessionStore.SessionData data = CryptoSessionStore.getSessionData();
            if (data == null || data.encKey == null || data.macKey == null) {
                return null;
            }
            byte[] iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP);
            byte[] cipherData = Base64.decode(obj.getString("data"), Base64.NO_WRAP);
            byte[] mac = Base64.decode(obj.getString("mac"), Base64.NO_WRAP);

            byte[] expected = hmac(data.macKey, concat(iv, cipherData));
            if (!MessageDigest.isEqual(mac, expected)) {
                return null;
            }

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(data.encKey, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(cipherData);
            if (gzipCompressed) {
                plain = gunzip(plain);
            }
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(256, compressed.length * 2));
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        input.close();
        return output.toByteArray();
    }

    private static byte[] sha256(byte[] input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(input);
        return md.digest();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] normalizeSecret(byte[] secret, int size) {
        if (secret.length == size) {
            return secret;
        }
        byte[] out = new byte[size];
        if (secret.length > size) {
            System.arraycopy(secret, secret.length - size, out, 0, size);
        } else {
            System.arraycopy(secret, 0, out, size - secret.length, secret.length);
        }
        return out;
    }
}
