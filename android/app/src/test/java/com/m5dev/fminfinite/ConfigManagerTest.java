package com.m5dev.fminfinite;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import java.io.File;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Context mockContext;
    private File testDir;

    @Before
    public void setUp() throws Exception {
        mockContext = mock(Context.class);
        testDir = tempFolder.newFolder("files");
        when(mockContext.getFilesDir()).thenReturn(testDir);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
    }

    @Test
    public void testSaveAndLoadConfig() {
        Config config = new Config();
        config.biosPath = "test_bios_path";
        config.renderer = "gpu";
        config.biosSetupComplete = true;

        ConfigManager.saveConfig(mockContext, config);

        Config loaded = ConfigManager.loadConfig(mockContext);
        assertEquals("test_bios_path", loaded.biosPath);
        assertEquals("gpu", loaded.renderer);
        assertTrue(loaded.biosSetupComplete);
    }

    @Test
    public void testRendererHelpers() {
        ConfigManager.setRenderer(mockContext, "software");
        assertEquals("software", ConfigManager.getRenderer(mockContext));

        ConfigManager.setRenderer(mockContext, "gpu");
        assertEquals("gpu", ConfigManager.getRenderer(mockContext));
    }

    @Test
    public void testConfigManagerThreadSafety() throws InterruptedException {
        final Config config = new Config();
        config.biosPath = "thread_safety_path";

        Thread thread1 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                ConfigManager.saveConfig(mockContext, config);
            }
        });

        Thread thread2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                Config loaded = ConfigManager.loadConfig(mockContext);
                assertNotNull(loaded);
            }
        });

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        Config loaded = ConfigManager.loadConfig(mockContext);
        assertEquals("thread_safety_path", loaded.biosPath);
    }
}
