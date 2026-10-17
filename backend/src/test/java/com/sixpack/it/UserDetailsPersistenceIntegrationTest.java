package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.user.domain.Address;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserDetails;
import com.sixpack.user.domain.UserStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class UserDetailsPersistenceIntegrationTest extends IntegrationTest {

    private static Address home() {
        return new Address("addr-1", "Home", "Asha", "9876543210", "12 MG Road", "Near park", "Pune", "MH",
                "India", "411001", true);
    }

    @Test
    void roundTripsEveryNewField() {
        Instant seen = Instant.parse("2026-10-17T10:00:00Z");
        User saved = users.save(new User(null, "Asha", "asha@test.dev", "hash", "", Role.USER, null,
                LocalDate.of(1995, 3, 4), null,
                new UserDetails("+919876543210", "en-IN", true, UserStatus.SUSPENDED, seen, List.of(home()))));

        User loaded = users.findById(saved.id()).orElseThrow();

        assertThat(loaded.details()).isEqualTo(saved.details());
        assertThat(loaded.details().addresses()).containsExactly(home());
        assertThat(loaded.details().status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void legacyDocumentWithoutNewFieldsLoadsWithDefaults() {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("name", "Old")
                .append("email", "old@test.dev").append("password", "hash").append("photo", "")
                .append("role", "user").append("dob", new java.util.Date(0)));

        User u = users.findById(id.toHexString()).orElseThrow();

        assertThat(u.details()).isEqualTo(UserDetails.EMPTY);
        assertThat(u.details().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void targetedUpdatesChangeOnlyTheirOwnFields() {
        User saved = users.save(User.newCustomer("Asha", "t@test.dev", "hash", "", null, LocalDate.of(1990, 1, 1)));

        users.updateProfile(saved.id(), null, "+919876543210", null);
        users.updateProfile(saved.id(), "Asha K", null, "en-IN");
        users.replaceAddresses(saved.id(), List.of(home()));
        users.touchLastLogin(saved.id(), Instant.parse("2026-10-17T10:00:00Z"));
        users.setStatus(saved.id(), UserStatus.SUSPENDED);

        User u = users.findById(saved.id()).orElseThrow();
        assertThat(u.name()).isEqualTo("Asha K");
        assertThat(u.email()).isEqualTo("t@test.dev");
        assertThat(u.passwordHash()).isEqualTo("hash");
        assertThat(u.details().phone()).isEqualTo("+919876543210");
        assertThat(u.details().locale()).isEqualTo("en-IN");
        assertThat(u.details().addresses()).containsExactly(home());
        assertThat(u.details().lastLoginAt()).isEqualTo(Instant.parse("2026-10-17T10:00:00Z"));
        assertThat(u.details().status()).isEqualTo(UserStatus.SUSPENDED);

        users.replaceAddresses(saved.id(), List.of());
        assertThat(users.findById(saved.id()).orElseThrow().details().addresses()).isEmpty();
    }
}
