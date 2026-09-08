package com.im.oldchat.api;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class SimpleWebSocketClient {

    public interface Listener {
        void onOpen();
        void onMessage(String text);
        void onClose(int code, String reason);
        void onError(Exception e);
    }

    private final URI uri;
    private final Map<String, String> headers;
    private final Listener listener;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;
    private volatile boolean running = false;

    public SimpleWebSocketClient(URI uri, Map<String, String> headers, Listener listener) {
        this.uri = uri;
        this.headers = headers;
        this.listener = listener;
    }

    public synchronized void connect() {
        if (running || readerThread != null) {
            return;
        }
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "ws-reader");
        readerThread.start();
    }

    public synchronized void close() {
        running = false;
        closeInternal();
    }

    public synchronized void send(String text) throws Exception {
        if (out == null || text == null) {
            return;
        }
        sendFrame(0x1, text.getBytes("UTF-8"));
    }

    /**
     * 发送 WebSocket Ping 心跳帧（Opcode 0x9）。
     * 用于在移动蜂窝网络（NAT 超时 30~60s）下主动保活。
     */
    public synchronized void sendPing() throws Exception {
        if (out == null || !running) {
            return;
        }
        sendFrame(0x9, new byte[0]);
    }

    private void runLoop() {
        try {
            openSocket();
            handshake();
            running = true;
            if (listener != null) {
                listener.onOpen();
            }
            readFrames();
        } catch (Exception e) {
            if (listener != null) {
                listener.onError(e);
            }
        } finally {
            running = false;
            closeInternal();
            synchronized (this) {
                readerThread = null;
            }
            if (listener != null) {
                listener.onClose(0, "");
            }
        }
    }

    private void openSocket() throws Exception {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "wss".equalsIgnoreCase(scheme) ? 443 : 80;
        }
        if ("wss".equalsIgnoreCase(scheme)) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(host, port);
            sslSocket.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sslSocket.getSession())) {
                try { sslSocket.close(); } catch (Exception ignored) { }
                throw new SSLHandshakeException("WebSocket hostname verification failed: " + host);
            }
            socket = sslSocket;
        } else {
            socket = connectWithFallback(host, port);
        }
        socket.setSoTimeout(75000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    private Socket connectWithFallback(String host, int port) throws Exception {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            addresses = new InetAddress[]{InetAddress.getByName(host)};
        }
        Exception last = null;
        for (InetAddress address : addresses) {
            try {
                Socket candidate = new Socket();
                try {
                    candidate.connect(new InetSocketAddress(address, port), 15000);
                } catch (Throwable t) {
                    candidate.close();
                    throw t;
                }
                return candidate;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("connection failed: " + host);
    }

    private void handshake() throws Exception {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        byte[] keyBytes = new byte[16];
        SimpleWebSocketFrameCodec.getRandom().nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(uri.getHost()).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                req.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        req.append("\r\n");
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        byte[] headerBytes = readHeader(in);
        String header = new String(headerBytes, "UTF-8");
        if (!header.startsWith("HTTP/1.1 101")) {
            String statusLine = header.split("\r\n")[0];
            throw new Exception("handshake failed: " + statusLine);
        }
        String accept = findHeader(header, "Sec-WebSocket-Accept");
        String expected = SimpleWebSocketFrameCodec.computeAccept(key);
        if (accept == null || !accept.trim().equals(expected)) {
            throw new Exception("bad accept");
        }
    }

    private byte[] readHeader(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] last = new byte[4];
        int count = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new Exception("eof");
            }
            baos.write(b);
            last[count % 4] = (byte) b;
            count++;
            if (count >= 4 && last[(count - 4) % 4] == '\r' && last[(count - 3) % 4] == '\n'
                    && last[(count - 2) % 4] == '\r' && last[(count - 1) % 4] == '\n') {
                break;
            }
        }
        return baos.toByteArray();
    }

    private String findHeader(String header, String key) {
        String[] lines = header.split("\r\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String name = line.substring(0, idx).trim();
                if (name.equalsIgnoreCase(key)) {
                    return line.substring(idx + 1).trim();
                }
            }
        }
        return null;
    }

    private void readFrames() throws Exception {
        while (running) {
            int b1 = in.read();
            if (b1 == -1) break;
            int b2 = in.read();
            if (b2 == -1) break;
            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long length = b2 & 0x7F;
            if (length == 126) {
                length = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (length == 127) {
                long l = 0;
                for (int i = 0; i < 8; i++) {
                    l = (l << 8) | (in.read() & 0xFF);
                }
                length = l;
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask, 4);
            }
            byte[] payload = new byte[(int) length];
            readFully(payload, (int) length);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            if (opcode == 0x1) {
                String msg = new String(payload, "UTF-8");
                if (listener != null) listener.onMessage(msg);
            } else if (opcode == 0x8) {
                sendFrame(0x8, new byte[0]);
                break;
            } else if (opcode == 0x9) {
                sendFrame(0xA, payload);
            }
        }
    }

    private void readFully(byte[] buffer, int length) throws Exception {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) throw new Exception("eof");
            offset += read;
        }
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        if (out == null) return;
        byte[] frameBytes = SimpleWebSocketFrameCodec.buildFrame(opcode, payload);
        out.write(frameBytes);
        out.flush();
    }

    private void closeInternal() {
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        in = null;
        out = null;
        socket = null;
    }
}
