package com.m5dev.fminfinite;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BiosScannerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testValidateBIOSCorrectCRC() throws IOException {
        File dummyFile = tempFolder.newFile("dummy.rom");
        try (FileOutputStream fos = new FileOutputStream(dummyFile)) {
            fos.write("Hello World".getBytes());
        }

        // CRC32 of "Hello World" in hex is "4a17b156"
        assertTrue(BiosScanner.validateBIOS(dummyFile, "4a17b156"));
        assertFalse(BiosScanner.validateBIOS(dummyFile, "11223344"));
    }

    @Test
    public void testValidateBIOSMissingFile() {
        File missingFile = new File(tempFolder.getRoot(), "does_not_exist.rom");
        assertFalse(BiosScanner.validateBIOS(missingFile, "4a17b156"));
        assertFalse(BiosScanner.validateBIOS(null, "4a17b156"));
    }
}
