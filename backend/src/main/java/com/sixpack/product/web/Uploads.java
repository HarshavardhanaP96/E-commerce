package com.sixpack.product.web;

import com.sixpack.shared.ApiException;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

final class Uploads {
    private Uploads() {
    }

    /** @return the file's bytes, or null when no file was sent */
    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("photo: could not be read");
        }
    }
}
