package com.sixpack.marketing.domain;

import java.util.regex.Pattern;

/**
 * Server-side conversion settings of one provider. {@code testEventCode} is Meta's code for Events Manager → Test
 * events; it is used only by the test-event button, never on real purchases. {@code secret} is sealed (ADR-029).
 */
public record ServerSettings(boolean enabled, String testEventCode, SealedSecret secret) {

    // Declared before OFF: static fields initialise in order and OFF runs the constructor.
    private static final Pattern TEST_CODE = Pattern.compile("^[A-Za-z0-9]{1,32}$");
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{16,512}$");

    public static final ServerSettings OFF = new ServerSettings(false, null, null);
    /** The associated-data field name the secret is sealed under. */
    public static final String SECRET_FIELD = "server.secret";

    public ServerSettings {
        testEventCode = testEventCode == null || testEventCode.isBlank() ? null : testEventCode.strip();
        if (testEventCode != null && !TEST_CODE.matcher(testEventCode).matches()) {
            throw new IllegalArgumentException("server.testEventCode: use 1-32 letters or digits");
        }
    }

    public boolean hasSecret() {
        return secret != null;
    }

    /** The pasted secret, stripped, or IllegalArgumentException naming the field. Never echoes the value. */
    public static String checkSecret(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        if (!SECRET.matcher(stripped).matches()) {
            throw new IllegalArgumentException("server.secret: use 16-512 letters, digits, _ or -");
        }
        return stripped;
    }
}
