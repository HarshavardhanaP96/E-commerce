package com.sixpack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.shared.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangesTest {

    private static final FieldPolicy POLICY = Changes.policy(List.of("price", "stock", "tags", "startsAt"),
            List.of("email"));

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void reportsOnlyTheFieldsThatChanged() {
        var before = map("price", 10.0, "stock", 3);
        var after = map("price", 12.0, "stock", 3);

        assertThat(Changes.diff(before, after, POLICY))
                .containsExactly(FieldChange.of("price", 10.0, 12.0));
    }

    @Test
    void nullToNullIsNotAChange() {
        assertThat(Changes.diff(map("price", null), map("price", null), POLICY)).isEmpty();
    }

    @Test
    void ignoresFieldsOutsideThePolicyEvenWhenTheyChange() {
        var before = map("price", 10.0, "passwordHash", "old");
        var after = map("price", 10.0, "passwordHash", "new");

        assertThat(Changes.diff(before, after, POLICY)).isEmpty();
    }

    @Test
    void personalFieldsAreFlaggedWithoutTheirValues() {
        var changes = Changes.diff(map("email", "a@x.dev"), map("email", "b@x.dev"), POLICY);

        assertThat(changes).containsExactly(FieldChange.redacted("email"));
        assertThat(changes.get(0).before()).isNull();
        assertThat(changes.get(0).after()).isNull();
        assertThat(changes.get(0).redacted()).isTrue();
    }

    @Test
    void anUnchangedPersonalFieldIsNotListed() {
        assertThat(Changes.diff(map("email", "a@x.dev"), map("email", "a@x.dev"), POLICY)).isEmpty();
    }

    @Test
    void aCreationListsEveryFieldThatHasAValue() {
        var changes = Changes.diff(Map.of(), map("price", 5.0, "stock", 2, "email", "a@x.dev"), POLICY);

        assertThat(changes).containsExactlyInAnyOrder(FieldChange.of("price", null, 5.0),
                FieldChange.of("stock", null, 2), FieldChange.redacted("email"));
    }

    @Test
    void aDeletionListsEveryFieldThatHadAValue() {
        var changes = Changes.diff(map("price", 5.0), Map.of(), POLICY);

        assertThat(changes).containsExactly(FieldChange.of("price", 5.0, null));
    }

    @Test
    void collectionsAndOtherTypesAreStoredAsPlainValues() {
        var when = Instant.parse("2026-10-18T10:00:00Z");
        var changes = Changes.diff(map("tags", List.of("a")), map("tags", List.of("a", "b")), POLICY);
        assertThat(changes).containsExactly(FieldChange.of("tags", List.of("a"), List.of("a", "b")));

        assertThat(Changes.diff(Map.of(), map("startsAt", when), POLICY))
                .containsExactly(FieldChange.of("startsAt", null, "2026-10-18T10:00:00Z"));
    }

    @Test
    void anUnknownActionIsABadRequest() {
        assertThat(AuditAction.parse(" product_created ")).isEqualTo(AuditAction.PRODUCT_CREATED);
        assertThatThrownBy(() -> AuditAction.parse("NOPE")).isInstanceOf(ApiException.class);
    }
}
