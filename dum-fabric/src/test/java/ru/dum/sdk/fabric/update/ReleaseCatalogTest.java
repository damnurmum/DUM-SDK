package ru.dum.sdk.fabric.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseCatalogTest {
    @TempDir Path root;

    private static String page(int first, int last) {
        var data = new JsonArray();
        for (int i = first; i <= last; i++) {
            var item = new JsonObject();
            item.addProperty("tag_name", "v1.0." + i);
            item.addProperty("name", "Release " + i);
            item.addProperty("published_at", "2026-10-31T00:00:00Z");
            item.addProperty("body", "## Notes\nDescription " + i);
            item.addProperty("html_url", "https://untrusted.invalid/");
            data.add(item);
        }
        return data.toString();
    }

    private static void await(ReleaseCatalog catalog) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (catalog.loading() && System.nanoTime() < end) Thread.sleep(5);
        assertFalse(catalog.loading(), "Release operation completed");
    }

    @Test void paginationDeduplicatesTagsAndOfflineRefreshPreservesCachedDescriptions() throws Exception {
        var catalog = new ReleaseCatalog(root, n -> n == 1 ? page(1, 20) : page(20, 21));
        assertTrue(catalog.load(true)); await(catalog);
        assertEquals(20, catalog.snapshot().releases().size());
        assertTrue(catalog.snapshot().more());
        assertTrue(catalog.loadMore()); await(catalog);
        assertEquals(21, catalog.snapshot().releases().size());
        assertFalse(catalog.snapshot().more());
        var offline = new ReleaseCatalog(root, n -> { throw new IOException("offline"); });
        assertTrue(offline.load(true)); await(offline);
        assertEquals(21, offline.snapshot().releases().size());
        assertTrue(offline.snapshot().cached());
        assertTrue(offline.snapshot().error().contains("RELEASES_NETWORK"));
        assertEquals("## Notes\nDescription 1", offline.snapshot().releases().getFirst().notes());
    }

    @Test void concurrentLoadsAreRejectedAndFreshCacheNeedsNoNetwork() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var catalog = new ReleaseCatalog(root, page -> {
            entered.countDown();
            assertTrue(finish.await(3, TimeUnit.SECONDS));
            return page(1, 1);
        });
        assertTrue(catalog.load(true));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertFalse(catalog.load(true));
        assertFalse(catalog.loadMore());
        finish.countDown(); await(catalog);
        var cached = new ReleaseCatalog(root, page -> { fail("Fresh cache must not fetch"); return "[]"; });
        assertTrue(cached.load(false)); await(cached);
        assertEquals(1, cached.snapshot().releases().size());
        assertTrue(cached.snapshot().cached());
    }

    @Test void brokenMetadataIsNotPresentedAsAnEmptySuccessfulResult() throws Exception {
        var catalog = new ReleaseCatalog(root, page -> "{\"message\":\"bad data\"}");
        assertTrue(catalog.load(true)); await(catalog);
        assertTrue(catalog.snapshot().releases().isEmpty());
        assertTrue(catalog.snapshot().error().contains("RELEASES_DATA"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseCatalog.parse(page(1, 21)));
    }

    @Test void releaseLinksIgnoreRemoteHtmlUrlsAndEncodeTagsOnTheFixedRepository() {
        var release = ReleaseCatalog.parse(page(1, 1)).getFirst();
        assertEquals("https://github.com/damnurmum/DUM-SDK/releases/tag/v1.0.1", release.url());
        var unusual = new ReleaseCatalog.Release("x/../../?", "", "", "", false);
        assertEquals("github.com", java.net.URI.create(unusual.url()).getHost());
        assertTrue(unusual.url().startsWith(SdkUpdater.DOWNLOAD_PAGE + "/tag/"));
    }
}
