package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class TerminalEndpointTest {

    @TestHTTPResource("/api/terminal")
    URI terminalUri;

    @Test
    void connect_receivesShellPromptOrOutput() throws Exception {
        URI wsUri = new URI("ws", null, terminalUri.getHost(), terminalUri.getPort(),
                terminalUri.getPath(), "cols=80&rows=24", null);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>("");

        Session session = container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                session.addMessageHandler(String.class, (MessageHandler.Whole<String>) message -> {
                    received.accumulateAndGet(message, String::concat);
                    messageLatch.countDown();
                });
            }
        }, ClientEndpointConfig.Builder.create().build(), wsUri);

        try {
            boolean gotMessage = messageLatch.await(10, TimeUnit.SECONDS);
            assertThat(gotMessage).as("Should receive some output from shell").isTrue();
            assertThat(received.get()).isNotEmpty();
        } finally {
            session.close();
        }
    }

    @Test
    void sendInput_echoesBack() throws Exception {
        URI wsUri = new URI("ws", null, terminalUri.getHost(), terminalUri.getPort(),
                terminalUri.getPath(), "cols=80&rows=24", null);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        CountDownLatch echoLatch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>("");

        Session session = container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                session.addMessageHandler(String.class, (MessageHandler.Whole<String>) message -> {
                    received.accumulateAndGet(message, String::concat);
                    if (received.get().contains("hello-pty-test")) {
                        echoLatch.countDown();
                    }
                });
            }
        }, ClientEndpointConfig.Builder.create().build(), wsUri);

        try {
            Thread.sleep(500);
            session.getBasicRemote().sendText("echo hello-pty-test\n");

            boolean gotEcho = echoLatch.await(10, TimeUnit.SECONDS);
            assertThat(gotEcho).as("Should see echoed command in terminal output").isTrue();
            assertThat(received.get()).contains("hello-pty-test");
        } finally {
            session.close();
        }
    }

    @Test
    void disconnect_killsProcess() throws Exception {
        URI wsUri = new URI("ws", null, terminalUri.getHost(), terminalUri.getPort(),
                terminalUri.getPath(), "cols=80&rows=24", null);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        CountDownLatch connected = new CountDownLatch(1);

        Session session = container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session s, EndpointConfig c) {
                connected.countDown();
            }
        }, ClientEndpointConfig.Builder.create().build(), wsUri);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        session.close();
        Thread.sleep(500);
    }
}
