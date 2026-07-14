package io.casehub.drafthouse;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@WebSocket(path = "/api/terminal")
public class TerminalEndpoint {

    private static final Logger LOG = Logger.getLogger(TerminalEndpoint.class.getName());

    private volatile PtyProcess process;
    private volatile Thread readerThread;

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        String query = connection.handshakeRequest().query();
        int cols = parseIntParam(query, "cols", 80);
        int rows = parseIntParam(query, "rows", 24);

        try {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) shell = "/bin/bash";

            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("TERM", "xterm-256color");

            process = new PtyProcessBuilder(new String[]{shell, "-l"})
                    .setEnvironment(env)
                    .setInitialColumns(cols)
                    .setInitialRows(rows)
                    .start();

            InputStream stdout = process.getInputStream();
            readerThread = Thread.ofVirtual().name("pty-reader-" + connection.id()).start(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = stdout.read(buf)) != -1) {
                        String text = new String(buf, 0, n);
                        connection.sendText(text).subscribe().with(
                                v -> {},
                                err -> LOG.fine("sendText failed: " + err.getMessage())
                        );
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        LOG.fine("PTY read ended: " + e.getMessage());
                    }
                }
            });

            LOG.info("Terminal session started: " + connection.id()
                    + " (" + cols + "x" + rows + ", shell=" + shell + ")");

        } catch (IOException e) {
            LOG.warning("Failed to start PTY: " + e.getMessage());
            connection.close().subscribe().with(v -> {}, err -> {});
        }
    }

    @OnTextMessage
    void onMessage(WebSocketConnection connection, String message) {
        PtyProcess p = this.process;
        if (p == null || !p.isAlive()) return;

        try {
            OutputStream stdin = p.getOutputStream();
            stdin.write(message.getBytes());
            stdin.flush();
        } catch (IOException e) {
            LOG.fine("Failed to write to PTY: " + e.getMessage());
        }
    }

    @OnClose
    void onClose(WebSocketConnection connection) {
        cleanup();
        LOG.info("Terminal session closed: " + connection.id());
    }

    private void cleanup() {
        Thread reader = this.readerThread;
        if (reader != null) {
            reader.interrupt();
            this.readerThread = null;
        }

        PtyProcess p = this.process;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            this.process = null;
        }
    }

    private static int parseIntParam(String query, String name, int defaultValue) {
        if (query == null) return defaultValue;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }
}
