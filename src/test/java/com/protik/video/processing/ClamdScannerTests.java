package com.protik.video.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protik.video.processing.MalwareScanner.ScannerUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The clamd protocol against a fake daemon. Nothing here proves the real ClamAV behaves the same way. */
class ClamdScannerTests {

    ServerSocket server;
    CompletableFuture<byte[]> received = new CompletableFuture<>();

    @AfterEach
    void close() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Starts a daemon that reads the whole INSTREAM request, then runs {@code reply} on the socket. */
    private ClamdScanner daemon(Consumer<Socket> reply) throws IOException {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                var in = new DataInputStream(socket.getInputStream());
                byte[] command = new byte[10];
                in.readFully(command);
                var payload = new ByteArrayOutputStream();
                while (true) {
                    int length = in.readInt();
                    if (length == 0) {
                        break;
                    }
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    payload.write(chunk);
                }
                received.complete(payload.toByteArray());
                reply.accept(socket);
            } catch (IOException ignored) {
                received.complete(new byte[0]);
            }
        });
        return new ClamdScanner("127.0.0.1", server.getLocalPort(), 2000, 2000);
    }

    private static void say(Socket socket, String text) {
        try {
            socket.getOutputStream().write((text + "\0").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @Test
    void aCleanFileIsReportedCleanAndTheDaemonGetsEveryByte() throws Exception {
        var scanner = daemon(s -> say(s, "stream: OK"));
        byte[] file = new byte[200_000];
        Arrays.fill(file, (byte) 7);
        var result = scanner.scan(stream(file));
        assertThat(result.clean()).isTrue();
        assertThat(received.get()).isEqualTo(file);
    }

    @Test
    void aDetectionCarriesTheSignatureName() throws Exception {
        var scanner = daemon(s -> say(s, "stream: Win.Test.EICAR_HDB-1 FOUND"));
        var result = scanner.scan(stream("X5O!P%@AP".getBytes(StandardCharsets.US_ASCII)));
        assertThat(result.clean()).isFalse();
        assertThat(result.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
    }

    @Test
    void anEmptyFileStillGetsAVerdict() throws Exception {
        var scanner = daemon(s -> say(s, "stream: OK"));
        assertThat(scanner.scan(stream(new byte[0])).clean()).isTrue();
        assertThat(received.get()).isEmpty();
    }

    @Test
    void everyOtherAnswerIsAnErrorAndNeverAPass() throws Exception {
        for (String reply : new String[] {"INSTREAM size limit exceeded. ERROR", "stream: some failure ERROR", "", "OK",
                "stream: OK extra", "stream:OK", "garbage", "stream: FOUND not really", "x".repeat(50)}) {
            received = new CompletableFuture<>();
            var scanner = daemon(s -> say(s, reply));
            assertThatThrownBy(() -> scanner.scan(stream(new byte[10]))).as(reply).isInstanceOf(ScannerUnavailableException.class);
            server.close();
        }
    }

    @Test
    void aDaemonThatClosesWithoutAnswerIsAnError() throws Exception {
        var scanner = daemon(s -> { });
        assertThatThrownBy(() -> scanner.scan(stream(new byte[10]))).isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void anOversizedReplyIsAnError() throws Exception {
        var scanner = daemon(s -> {
            try {
                s.getOutputStream().write("y".repeat(5000).getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThatThrownBy(() -> scanner.scan(stream(new byte[10]))).isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void aSilentDaemonTimesOutAsAnError() throws Exception {
        var scanner = daemon(s -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> scanner.scan(stream(new byte[10]))).isInstanceOf(ScannerUnavailableException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(4500);
    }

    @Test
    void noDaemonIsAnErrorNotAPass() throws Exception {
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        var scanner = new ClamdScanner("127.0.0.1", port, 500, 500);
        assertThatThrownBy(() -> scanner.scan(stream(new byte[10]))).isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void aFailingSourceStreamIsAnErrorToo() throws Exception {
        var scanner = daemon(s -> say(s, "stream: OK"));
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk error");
            }
        };
        assertThatThrownBy(() -> scanner.scan(broken)).isInstanceOf(IOException.class);
    }

    @Test
    void theStandInNeverClaimsToHaveScanned() {
        var stand = new UnscannedScanner();
        assertThat(stand.scans()).isFalse();
        assertThat(new ClamdScanner("localhost", 3310, 1, 1).scans()).isTrue();
    }
}
