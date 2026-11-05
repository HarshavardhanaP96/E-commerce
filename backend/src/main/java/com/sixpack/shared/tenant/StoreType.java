package com.sixpack.shared.tenant;

import com.sixpack.shared.ApiException;
import java.util.Locale;
import java.util.Map;

/**
 * The kinds of store the platform onboards. A preset is applied once, when the store is created: its values become
 * the store's own, and afterwards the type is only a label (ADR-030). {@code marketing} is never part of a preset.
 */
public enum StoreType {
    GENERAL("General", "Anything else. Starts on the platform defaults.", Map.of(), StoreSettings.NONE),
    FASHION("Fashion", "Clothing, shoes and accessories in sizes and colours.", Map.of("variants", true),
            new StoreSettings(new StoreSettings.Commerce(null, null, 5.0, null, null, null, null),
                    new StoreSettings.Catalog(null, 3), null, null)),
    GROCERY("Grocery", "Food and daily needs: low GST, cheap local delivery, quick turnaround.",
            Map.of("variants", false),
            new StoreSettings(new StoreSettings.Commerce(40.0, 500.0, 5.0, null, null, 1, null),
                    new StoreSettings.Catalog(null, 10), null, null)),
    ELECTRONICS("Electronics", "Gadgets and appliances with storage or colour options.", Map.of("variants", true),
            new StoreSettings(new StoreSettings.Commerce(null, null, 18.0, null, null, 2, null),
                    new StoreSettings.Catalog(null, 2), null, null));

    private final String label;
    private final String description;
    private final Map<String, Boolean> features;
    private final StoreSettings settings;

    StoreType(String label, String description, Map<String, Boolean> features, StoreSettings settings) {
        this.label = label;
        this.description = description;
        this.features = features;
        this.settings = settings;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Feature switches the preset sets, by {@link Feature} key. */
    public Map<String, Boolean> features() {
        return features;
    }

    /** Shop rules the preset sets; null fields are left to the platform default. */
    public StoreSettings settings() {
        return settings;
    }

    /** Blank → {@code GENERAL}; otherwise the type's name in any case. */
    public static StoreType parse(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("type: unknown store type");
        }
    }
}
