package com.sixpack.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageTypeTest {

    @Test
    void detectsSupportedFormatsByMagicBytes() {
        assertThat(ImageType.detect(TestImages.png())).contains(ImageType.PNG);
        assertThat(ImageType.detect(pad(0xFF, 0xD8, 0xFF, 0xE0))).contains(ImageType.JPEG);
        assertThat(ImageType.detect("GIF89a......".getBytes(StandardCharsets.US_ASCII))).contains(ImageType.GIF);
        assertThat(ImageType.detect("RIFF....WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).contains(ImageType.WEBP);
    }

    @Test
    void rejectsHtmlAndSvgEvenWithImageNames() {
        assertThat(ImageType.detect("<html><script>alert(1)</script></html>".getBytes())).isEmpty();
        assertThat(ImageType.detect("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes())).isEmpty();
        assertThat(ImageType.detect(new byte[3])).isEmpty();
    }

    private static byte[] pad(int... head) {
        byte[] b = new byte[16];
        for (int i = 0; i < head.length; i++) {
            b[i] = (byte) head[i];
        }
        return b;
    }
}
