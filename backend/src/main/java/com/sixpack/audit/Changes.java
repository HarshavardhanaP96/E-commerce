package com.sixpack.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the change list for an audit entry. Only fields named by the entity's {@link FieldPolicy} are ever
 * emitted, so a field added to an entity later is not logged until someone decides it should be.
 */
public final class Changes {

    /**
     * @param values   fields logged with their before/after values (prices, stock, status, role…)
     * @param personal fields logged as "changed" only, with no values (email, name, phone…)
     */
    public record FieldPolicy(Set<String> values, Set<String> personal) {
    }

    private Changes() {
    }

    public static FieldPolicy policy(Collection<String> values, Collection<String> personal) {
        return new FieldPolicy(new LinkedHashSet<>(values), new LinkedHashSet<>(personal));
    }

    /**
     * @param before the snapshot before the change; {@code Map.of()} for a creation
     * @param after  the snapshot after the change; {@code Map.of()} for a deletion
     */
    public static List<FieldChange> diff(Map<String, ?> before, Map<String, ?> after, FieldPolicy policy) {
        List<FieldChange> changes = new ArrayList<>();
        for (String field : policy.values()) {
            Object b = plain(before.get(field));
            Object a = plain(after.get(field));
            if (!Objects.equals(b, a)) {
                changes.add(FieldChange.of(field, b, a));
            }
        }
        for (String field : policy.personal()) {
            if (!Objects.equals(before.get(field), after.get(field))) {
                changes.add(FieldChange.redacted(field));
            }
        }
        return List.copyOf(changes);
    }

    /** Strings, numbers and booleans pass through; collections are normalised; anything else becomes its text. */
    private static Object plain(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(Changes::plain).toList();
        }
        return value.toString();
    }
}
