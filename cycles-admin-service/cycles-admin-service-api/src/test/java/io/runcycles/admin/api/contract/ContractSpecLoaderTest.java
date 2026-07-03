package io.runcycles.admin.api.contract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractSpecLoaderTest {

    private static final Path CACHE_PATH = Path.of("target", "contract", "spec.yaml");

    private String previousSpecUrl;
    private boolean hadCache;
    private String previousCacheContent;
    private FileTime previousCacheMtime;

    @BeforeEach
    void setUp() throws Exception {
        previousSpecUrl = System.getProperty("contract.spec.url");
        hadCache = Files.exists(CACHE_PATH);
        if (hadCache) {
            previousCacheContent = Files.readString(CACHE_PATH);
            previousCacheMtime = Files.getLastModifiedTime(CACHE_PATH);
        }
        System.clearProperty("contract.spec.url");
        clearInMemoryCache();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (previousSpecUrl == null) {
            System.clearProperty("contract.spec.url");
        } else {
            System.setProperty("contract.spec.url", previousSpecUrl);
        }
        clearInMemoryCache();
        restoreCacheFile();
    }

    @Test
    void fileUrlOverrideLoadsLocalSpec(@TempDir Path tempDir) throws Exception {
        Path spec = tempDir.resolve("spec.yaml");
        Files.writeString(spec, "local-spec");

        System.setProperty("contract.spec.url", spec.toUri().toString());

        assertEquals("local-spec", ContractSpecLoader.loadSpec());
    }

    @Test
    void explicitOverrideWinsOverFreshOnDiskCache(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(CACHE_PATH.getParent());
        Files.writeString(CACHE_PATH, "cached-spec",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path spec = tempDir.resolve("spec.yaml");
        Files.writeString(spec, "override-spec");
        System.setProperty("contract.spec.url", spec.toUri().toString());

        assertEquals("override-spec", ContractSpecLoader.loadSpec());
    }

    private static void clearInMemoryCache() throws Exception {
        Field cached = ContractSpecLoader.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, null);
    }

    private void restoreCacheFile() throws Exception {
        if (hadCache) {
            Files.createDirectories(CACHE_PATH.getParent());
            Files.writeString(CACHE_PATH, previousCacheContent,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setLastModifiedTime(CACHE_PATH, previousCacheMtime);
        } else {
            Files.deleteIfExists(CACHE_PATH);
        }
    }
}
