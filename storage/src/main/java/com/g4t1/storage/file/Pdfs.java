package com.g4t1.storage.file;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Pdfs {

    private static final byte[] MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private Pdfs() {
    }

    // uploads and downloads both get checked; publishers often answer a PDF link with an HTML page
    public static boolean isPdf(byte[] bytes) {
        return bytes != null
                && bytes.length >= MAGIC.length
                && Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }
}
