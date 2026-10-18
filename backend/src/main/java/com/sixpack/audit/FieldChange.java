package com.sixpack.audit;

/**
 * One field of an entity that changed. A personal field has {@code redacted = true} and no values: the log says
 * that it changed, never what it was.
 */
public record FieldChange(String field, Object before, Object after, boolean redacted) {

    public static FieldChange of(String field, Object before, Object after) {
        return new FieldChange(field, before, after, false);
    }

    public static FieldChange redacted(String field) {
        return new FieldChange(field, null, null, true);
    }
}
