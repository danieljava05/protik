package com.protik.video.processing;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Scans through a clamd daemon with the {@code INSTREAM} command (no client library needed). Every answer other than a
 * plain "OK" or "FOUND" is an error, never a pass: an oversized stream, a daemon that closes early and a garbled reply all
 * become {@link ScannerUnavailableException}. The daemon's {@code StreamMaxLength} must be at least our upload limit.
 *
 * <p>Tested against a fake daemon only; it has not been run against a real ClamAV (ADR 0022).
 */
public class ClamdScanner implements MalwareScanner {

    private static final int CHUNK = 64 * 1024;
    private static final int MAX_REPLY = 1024;

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ClamdScanner(String host, int port, int connectTimeoutMillis, int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public boolean scans() {
        return true;
    }

    @Override
    public Result scan(InputStream content) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            OutputStream raw = socket.getOutputStream();
            var out = new DataOutputStream(raw);
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                out.writeInt(read);
                out.write(buffer, 0, read);
            }
            out.writeInt(0);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (ScannerUnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new ScannerUnavailableException("The virus scanner could not be used: " + e.getMessage(), e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        var reply = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != 0 && b != '\n') {
            reply.append((char) b);
            if (reply.length() > MAX_REPLY) {
                throw new ScannerUnavailableException("The virus scanner sent an oversized reply");
            }
        }
        return reply.toString();
    }

    /** "stream: OK", "stream: Eicar-Test-Signature FOUND", or an error text. */
    static Result parse(String reply) throws ScannerUnavailableException {
        String text = reply.strip();
        if (text.endsWith(" FOUND")) {
            String body = text.substring(0, text.length() - " FOUND".length());
            int colon = body.indexOf(':');
            String signature = (colon >= 0 ? body.substring(colon + 1) : body).strip();
            return Result.infected(signature.isEmpty() ? "unknown" : signature);
        }
        if (text.equals("stream: OK")) {
            return Result.ok();
        }
        throw new ScannerUnavailableException("The virus scanner gave no verdict: "
                + (text.length() > 100 ? text.substring(0, 100) : text));
    }
}
