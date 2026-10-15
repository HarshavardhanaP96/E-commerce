package com.sixpack.storage;

import java.util.Base64;

public final class TestImages {

    private TestImages() {
    }

    /** A valid 1x1 PNG. */
    public static byte[] png() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }
}
