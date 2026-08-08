package com.m5dev.fminfinite;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import org.junit.Test;

public class StorageHelperTest {

    @Test
    public void testSyncStorageWithNullUriThrowsException() {
        Context mockContext = mock(Context.class);
        try {
            StorageHelper.syncStorage(mockContext, null);
            fail("Expected IOException to be thrown for null Uri");
        } catch (IOException e) {
            assertEquals("Root URI is null", e.getMessage());
        }
    }
}
