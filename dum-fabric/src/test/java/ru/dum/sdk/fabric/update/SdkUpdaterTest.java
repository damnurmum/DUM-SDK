package ru.dum.sdk.fabric.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.dum.sdk.fabric.update.UpdateStatus.Stage.*;

class SdkUpdaterTest {
    @TempDir Path root;
    private static final byte[] CURRENT_RELEASE = "{\"tag_name\":\"v0.14.2\"}".getBytes(StandardCharsets.UTF_8);

    private SdkUpdater updater(SdkUpdater.Download download, SdkUpdater.Pause pause) {
        return new SdkUpdater(root, "0.14.2", "release", download,
                (path, version) -> fail("No invalid update may start a helper"), pause, true, false);
    }

    private static UpdateStatus run(SdkUpdater updater, List<UpdateStatus> events) throws Exception {
        var done = new CompletableFuture<UpdateStatus>();
        assertTrue(updater.checkInBackground(status -> {
            events.add(status);
            if (status.stage() == CURRENT || status.stage() == READY || status.stage() == FAILED) done.complete(status);
        }));
        return done.get(5, TimeUnit.SECONDS);
    }

    @Test void disconnectedNetworkHasTwoRetriesThenActionableFinalError() throws Exception {
        var attempts = new AtomicInteger();
        var waits = new ArrayList<Long>();
        var events = new ArrayList<UpdateStatus>();
        var updater = updater((url, limit) -> { attempts.incrementAndGet(); throw new UnknownHostException("offline"); }, waits::add);
        UpdateStatus result = run(updater, events);
        assertEquals(FAILED, result.stage());
        assertEquals("UPDATE_DNS", result.errorCode());
        assertTrue(result.message().contains("интернет"));
        assertTrue(result.message().contains("вручную с GitHub"));
        assertEquals(3, attempts.get());
        assertEquals(List.of(5000L, 15000L), waits);
        assertEquals(2, events.stream().filter(s -> s.stage() == RETRY_WAIT).count());
        assertNull(updater.pending());
        assertFalse(Files.exists(root.resolve("sdk-update-plan.txt")));
    }

    @Test void networkRecoveryKeepsOneWorkerAndCompletesWithoutFalseFailure() throws Exception {
        var requests = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CompletableFuture<UpdateStatus>();
        var updater = updater((url, limit) -> {
            if (requests.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                throw new java.net.ConnectException("offline");
            }
            return CURRENT_RELEASE;
        }, millis -> { });
        assertTrue(updater.checkInBackground(s -> { if (s.stage() == CURRENT) done.complete(s); }));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertFalse(updater.canCheck());
        assertFalse(updater.checkInBackground(s -> fail("Concurrent update")));
        release.countDown();
        assertEquals(CURRENT, done.get(5, TimeUnit.SECONDS).stage());
        assertEquals(2, requests.get());
    }

    @Test void wrongSignatureIsTerminalAndNeverStagesAnUpdate() throws Exception {
        byte[] release = ("{\"tag_name\":\"v0.14.3\",\"assets\":["
                + "{\"name\":\"DUM-SDK.jar\",\"browser_download_url\":\"jar\"},"
                + "{\"name\":\"DUM-SDK.jar.sig\",\"browser_download_url\":\"sig\"}]}").getBytes(StandardCharsets.UTF_8);
        var events = new ArrayList<UpdateStatus>();
        UpdateStatus result = run(updater((url, limit) -> url.equals("release") ? release : new byte[64],
                millis -> fail("A bad signature must not be retried")), events);
        assertEquals("UPDATE_SIGNATURE", result.errorCode());
        assertEquals(List.of(CHECKING, DOWNLOADING, VERIFYING, FAILED), events.stream().map(UpdateStatus::stage).toList());
        assertFalse(Files.exists(root.resolve("update")));
    }

    @Test void malformedReleaseAndUnsafeVersionAreNotNetworkErrors() throws Exception {
        for (String release : List.of("not json", "{\"tag_name\":\"../../outside\",\"assets\":[]}")) {
            UpdateStatus result = run(updater((url, limit) -> release.getBytes(StandardCharsets.UTF_8),
                    millis -> fail("Bad metadata must not be retried")), new ArrayList<>());
            assertEquals("UPDATE_RELEASE", result.errorCode());
            assertEquals(1, result.attempt());
        }
    }

    @Test void realHttpTransportClassifiesStatusAndSize() throws Exception {
        String previous = System.getProperty("dumsdk.updateTest");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/error") ? 503 : 200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        System.setProperty("dumsdk.updateTest", "true");
        try {
            var transport = SdkUpdater.httpDownload();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var unavailable = assertThrows(SdkUpdater.Failure.class, () -> transport.get(base + "/error", 100));
            assertEquals("UPDATE_HTTP_503", unavailable.code);
            assertTrue(unavailable.retry);
            var oversized = assertThrows(SdkUpdater.Failure.class, () -> transport.get(base, 2));
            assertEquals("UPDATE_SIZE", oversized.code);
            assertFalse(oversized.retry);
        } finally {
            server.stop(0);
            if (previous == null) System.clearProperty("dumsdk.updateTest"); else System.setProperty("dumsdk.updateTest", previous);
        }
    }

    @Test void brokenNotificationConsumerDoesNotTriggerDownloadRetries() throws Exception {
        var calls = new AtomicInteger();
        var updater = updater((url, limit) -> { calls.incrementAndGet(); return CURRENT_RELEASE; }, millis -> fail("Unexpected retry"));
        assertTrue(updater.checkInBackground(status -> { throw new IllegalStateException("UI unavailable"); }));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (updater.status().stage() != CURRENT && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(CURRENT, updater.status().stage());
        assertEquals(1, calls.get());
    }
}
