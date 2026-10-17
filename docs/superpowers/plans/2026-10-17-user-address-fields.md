# User Profile and Address Book Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `User` the Tier 1 profile fields (phone, locale, emailVerified, status, lastLoginAt) and a saved address book (up to 10 addresses, one default), with self-service endpoints, a suspend/reactivate switch for admins, and a saved-address picker at checkout.

**Architecture:** Same pattern as plan 1: new optional fields live in a nested `UserDetails` record on `User`, so the 9-argument constructor and every call site keep compiling, and stored `users` documents without the fields load with defaults (no migration). Address rules (limit, single default, promotion on delete) live in a pure domain class `AddressBook`. Writes that must not clobber concurrent changes use targeted Mongo `$set` updates on the repository instead of whole-document saves.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13, Spring Data MongoDB (`MongoTemplate`), JUnit 5 + AssertJ + MockMvc + Testcontainers `mongo:7.0`; React + TypeScript + Redux Toolkit Query.

**Spec:** `docs/superpowers/specs/2026-10-17-richer-entities-design.md` (§4 Entities: User and Address rows, delivery step 2). Plan 2 of 4; plan 1 is `2026-10-17-product-category-fields.md`.

## Prerequisite (read before executing)

This plan edits files that already carry **uncommitted changes in the working tree** (the consent / auth / account work): `AuthService.java`, `AuthController.java`, `UserAdminService.java`, `MongoUserRepository.java`, `SecurityConfig.java`, `IntegrationTest.java`, and frontend `userAPI.ts`, `types.ts`, `api-types.ts`, `App.tsx`, `accountMenu.tsx`, `shipping.tsx`, plus `contracts.md`, `architecture.md`, `compliance-consent.md`. Commit or stash that work first (on its own branch), then branch from the result. Executing on top of a dirty tree makes per-task commits impossible to keep clean, which is what happened in plan 1.

## Global Constraints

- Keep JSON field names and `_id` (ADR-004). Existing response fields keep their types; new ones are additive.
- New fields are optional on read; old `users` documents must load with defaults. No migration, no new unique indexes.
- Module boundary: everything stays inside `com.sixpack.user` (plus `shared.security.SecurityConfig`, tests, frontend, docs). `ModularityTests` must stay green. `order` is not touched here (plan 3 will consume addresses).
- Errors use `ApiException` (`badRequest`, `notFound`, `conflict`, `forbidden`); responses use `ApiResponse.of(...)` / `ApiResponse.message(...)`.
- Update semantics: omitted or blank string fields mean "keep the current value" (as in plan 1). Addresses are replaced whole on `PUT`.
- Marketing preferences are **not** a `User` field. They already live in the append-only consent ledger (ADR-014); duplicating them would let the two disagree.
- Personal data minimisation (DPDP): `UserView` exposes phone, locale, status, lastLoginAt and emailVerified but **not** addresses; addresses are only readable through `/user/me/addresses` by their owner.
- Backend tests: `cd backend && mvn -q -Dtest=<Class> test` (Docker running). Final gate: `mvn verify`, `npm run build`, `npm run lint`.

## Review Focus

1. A legacy `users` document (no new fields) must load, log in, and be able to save an address (Task 2 and Task 3 tests).
2. The address book must always hold exactly one default when non-empty: first address, deleting the default, and setting a new default (Task 1 unit tests, Task 3 IT).
3. The 11th address, a malformed phone/pin code, and an unknown address id must return 409/400/404, never 500 (Task 3 tests).
4. One user must never read or change another user's addresses, and anonymous requests must get 401 (Task 3 tests).
5. A suspended user must be refused at login (403, checked after the password so status can't be probed), refused at `/me`, and an admin must not be able to suspend themselves (Task 4 tests).

---

## File Structure

Backend (`backend/src/main/java/com/sixpack/user/`):

| File | Responsibility |
|---|---|
| `domain/UserStatus.java` (create) | `ACTIVE`/`SUSPENDED`, lenient and strict parsing |
| `domain/Address.java` (create) | One saved address |
| `domain/AddressBook.java` (create) | Pure add/update/remove rules (limit 10, one default) |
| `domain/UserDetails.java` (create) | Immutable bundle of the new optional user fields, with `EMPTY` |
| `domain/User.java` (modify) | Add `details`, keep 9-arg constructor, preserve details in withers |
| `domain/UserRepository.java` (modify) | `updateProfile`, `replaceAddresses`, `touchLastLogin`, `setStatus` |
| `infra/mongo/UserDocument.java`, `MongoUserRepository.java` (modify) | Persist new fields, targeted updates |
| `application/ProfileService.java` (create) | Profile and address-book use cases |
| `application/AuthService.java` (modify) | Suspended check, last-login stamp |
| `application/UserAdminService.java` (modify) | `changeStatus` |
| `web/MeController.java` (create) | `PUT /user/me`, `/user/me/addresses...` |
| `web/UserAdminController.java`, `UserView.java` (modify) | Optional role/status update, new view fields |

Also `shared/security/SecurityConfig.java`, new tests, frontend files in Task 5, docs in Task 6.

---

### Task 1: Domain types (UserStatus, Address, AddressBook, UserDetails, User.details)

**Files:**
- Create: `backend/src/main/java/com/sixpack/user/domain/UserStatus.java`, `Address.java`, `AddressBook.java`, `UserDetails.java`
- Modify: `backend/src/main/java/com/sixpack/user/domain/User.java`
- Test: `backend/src/test/java/com/sixpack/user/domain/UserDomainTest.java`

**Interfaces:**
- Produces:
  - `enum UserStatus { ACTIVE, SUSPENDED; static UserStatus fromStored(String); static UserStatus parse(String) }` (`parse` throws `ApiException` 400)
  - `record Address(String id, String label, String name, String phone, String line1, String line2, String city, String state, String country, String pinCode, boolean isDefault)` with `Address withId(String)` and `Address withDefault(boolean)`
  - `AddressBook`: `static final int MAX = 10`; `static List<Address> add(List<Address> current, Address address)`; `static List<Address> update(List<Address> current, String id, Address replacement)`; `static List<Address> remove(List<Address> current, String id)`. All return a new list that is empty or has exactly one default. `add` throws `ApiException` 409 at the limit; `update`/`remove` throw `ApiException` 404 for an unknown id.
  - `record UserDetails(String phone, String locale, boolean emailVerified, UserStatus status, Instant lastLoginAt, List<Address> addresses)` with `static final UserDetails EMPTY`
  - `User(..., Instant createdAt, UserDetails details)` plus the old 9-arg constructor, `User withDetails(UserDetails)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/user/domain/UserDomainTest.java`:

```java
package com.sixpack.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserDomainTest {

    private static Address address(String id, boolean isDefault) {
        return new Address(id, "Home", "Asha", "9876543210", "12 MG Road", null, "Pune", "MH", "India", "411001",
                isDefault);
    }

    private static long defaults(List<Address> book) {
        return book.stream().filter(Address::isDefault).count();
    }

    @Test
    void firstAddressBecomesDefault() {
        List<Address> book = AddressBook.add(List.of(), address("a", false));
        assertThat(book).hasSize(1);
        assertThat(book.get(0).isDefault()).isTrue();
    }

    @Test
    void addingANewDefaultDemotesTheOldOne() {
        List<Address> book = AddressBook.add(List.of(), address("a", false));
        book = AddressBook.add(book, address("b", true));
        assertThat(defaults(book)).isEqualTo(1);
        assertThat(book).filteredOn(Address::isDefault).extracting(Address::id).containsExactly("b");
    }

    @Test
    void addingANonDefaultKeepsTheCurrentDefault() {
        List<Address> book = AddressBook.add(List.of(), address("a", false));
        book = AddressBook.add(book, address("b", false));
        assertThat(book).filteredOn(Address::isDefault).extracting(Address::id).containsExactly("a");
    }

    @Test
    void removingTheDefaultPromotesTheFirstRemaining() {
        List<Address> book = AddressBook.add(List.of(), address("a", true));
        book = AddressBook.add(book, address("b", false));
        book = AddressBook.add(book, address("c", false));

        book = AddressBook.remove(book, "a");

        assertThat(book).extracting(Address::id).containsExactly("b", "c");
        assertThat(book).filteredOn(Address::isDefault).extracting(Address::id).containsExactly("b");
        assertThat(AddressBook.remove(book, "b")).filteredOn(Address::isDefault).extracting(Address::id)
                .containsExactly("c");
        assertThat(AddressBook.remove(AddressBook.remove(book, "b"), "c")).isEmpty();
    }

    @Test
    void updateKeepsIdAndCannotLeaveTheBookWithoutADefault() {
        List<Address> book = AddressBook.add(List.of(), address("a", true));
        book = AddressBook.add(book, address("b", false));

        List<Address> updated = AddressBook.update(book, "a", address("ignored", false).withDefault(false));

        assertThat(updated).extracting(Address::id).containsExactly("a", "b");
        assertThat(defaults(updated)).isEqualTo(1);
        assertThat(updated.get(0).isDefault()).isTrue();

        List<Address> switched = AddressBook.update(book, "b", address("b", true));
        assertThat(switched).filteredOn(Address::isDefault).extracting(Address::id).containsExactly("b");
    }

    @Test
    void limitAndUnknownIdsAreRejected() {
        List<Address> book = new ArrayList<>();
        for (int i = 0; i < AddressBook.MAX; i++) {
            book = new ArrayList<>(AddressBook.add(book, address("id" + i, false)));
        }
        List<Address> full = book;
        assertThatThrownBy(() -> AddressBook.add(full, address("extra", false)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AddressBook.update(full, "nope", address("x", false)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AddressBook.remove(full, "nope")).isInstanceOf(ApiException.class);
    }

    @Test
    void emptyDetailsAreActiveAndUnverified() {
        UserDetails d = UserDetails.EMPTY;
        assertThat(d.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(d.emailVerified()).isFalse();
        assertThat(d.addresses()).isEmpty();
        assertThat(d.phone()).isNull();
        assertThat(new UserDetails(null, null, false, null, null, null)).isEqualTo(UserDetails.EMPTY);
    }

    @Test
    void legacyNineArgConstructorAndWithersKeepDetails() {
        User u = new User("1", "Asha", "a@b.dev", "hash", "", Role.USER, null, LocalDate.of(2000, 1, 1), null);
        assertThat(u.details()).isEqualTo(UserDetails.EMPTY);

        UserDetails custom = new UserDetails("+919876543210", "en-IN", false, UserStatus.SUSPENDED, null, List.of());
        User withDetails = u.withDetails(custom);

        assertThat(withDetails.withRole(Role.ADMIN).details()).isEqualTo(custom);
        assertThat(withDetails.withPasswordHash("x").details()).isEqualTo(custom);
    }

    @Test
    void statusParsing() {
        assertThat(UserStatus.parse(" suspended ")).isEqualTo(UserStatus.SUSPENDED);
        assertThat(UserStatus.fromStored(null)).isEqualTo(UserStatus.ACTIVE);
        assertThat(UserStatus.fromStored("junk")).isEqualTo(UserStatus.ACTIVE);
        assertThatThrownBy(() -> UserStatus.parse("banned")).isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=UserDomainTest test`
Expected: compilation FAIL (`Address`, `AddressBook`, `UserDetails`, `UserStatus` do not exist).

- [ ] **Step 3: Write minimal implementation**

`UserStatus.java`:

```java
package com.sixpack.user.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** Account state. SUSPENDED users cannot log in. */
public enum UserStatus {
    ACTIVE, SUSPENDED;

    /** Lenient read of a stored value: legacy documents without a status are ACTIVE. */
    public static UserStatus fromStored(String stored) {
        if (stored == null) {
            return ACTIVE;
        }
        try {
            return valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }

    /** Strict parse of user input. */
    public static UserStatus parse(String input) {
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("status: must be one of ACTIVE, SUSPENDED");
        }
    }
}
```

`Address.java`:

```java
package com.sixpack.user.domain;

/** A saved delivery address. {@code id} is a server-generated UUID, unique within one user's book. */
public record Address(String id, String label, String name, String phone, String line1, String line2, String city,
                      String state, String country, String pinCode, boolean isDefault) {

    public Address withId(String newId) {
        return new Address(newId, label, name, phone, line1, line2, city, state, country, pinCode, isDefault);
    }

    public Address withDefault(boolean value) {
        return new Address(id, label, name, phone, line1, line2, city, state, country, pinCode, value);
    }
}
```

`AddressBook.java`:

```java
package com.sixpack.user.domain;

import com.sixpack.shared.ApiException;
import java.util.ArrayList;
import java.util.List;

/** Rules for a user's saved addresses: at most {@link #MAX}, and exactly one default whenever there is any. */
public final class AddressBook {
    public static final int MAX = 10;

    private AddressBook() {
    }

    public static List<Address> add(List<Address> current, Address address) {
        if (current.size() >= MAX) {
            throw ApiException.conflict("You can save at most " + MAX + " addresses");
        }
        List<Address> next = new ArrayList<>(current);
        next.add(address);
        return normalize(next, address.isDefault() ? address.id() : null);
    }

    public static List<Address> update(List<Address> current, String id, Address replacement) {
        int index = indexOf(current, id);
        List<Address> next = new ArrayList<>(current);
        next.set(index, replacement.withId(id));
        return normalize(next, replacement.isDefault() ? id : null);
    }

    public static List<Address> remove(List<Address> current, String id) {
        int index = indexOf(current, id);
        List<Address> next = new ArrayList<>(current);
        next.remove(index);
        return normalize(next, null);
    }

    private static int indexOf(List<Address> book, String id) {
        for (int i = 0; i < book.size(); i++) {
            if (book.get(i).id().equals(id)) {
                return i;
            }
        }
        throw ApiException.notFound("Address not found");
    }

    /**
     * @param newDefaultId the address that must be the default, or null to keep the existing default
     */
    private static List<Address> normalize(List<Address> book, String newDefaultId) {
        if (book.isEmpty()) {
            return List.of();
        }
        String defaultId = newDefaultId;
        if (defaultId == null) {
            defaultId = book.stream().filter(Address::isDefault).map(Address::id).findFirst()
                    .orElse(book.get(0).id());
        }
        String chosen = defaultId;
        return book.stream().map(a -> a.withDefault(a.id().equals(chosen))).toList();
    }
}
```

`UserDetails.java`:

```java
package com.sixpack.user.domain;

import java.time.Instant;
import java.util.List;

/** Optional profile fields. Legacy users carry {@link #EMPTY}. */
public record UserDetails(
        String phone,
        String locale,
        boolean emailVerified,
        UserStatus status,
        Instant lastLoginAt,
        List<Address> addresses) {

    public static final UserDetails EMPTY = new UserDetails(null, null, false, null, null, null);

    public UserDetails {
        status = status == null ? UserStatus.ACTIVE : status;
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }
}
```

Replace `User.java`:

```java
package com.sixpack.user.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;

public record User(
        String id,
        String name,
        String email,
        String passwordHash,
        String photo,
        Role role,
        Gender gender,
        LocalDate dob,
        Instant createdAt,
        UserDetails details) {

    public User {
        details = details == null ? UserDetails.EMPTY : details;
    }

    /** Pre-details shape, kept so existing callers compile unchanged. */
    public User(String id, String name, String email, String passwordHash, String photo, Role role, Gender gender,
                LocalDate dob, Instant createdAt) {
        this(id, name, email, passwordHash, photo, role, gender, dob, createdAt, UserDetails.EMPTY);
    }

    public static User newCustomer(String name, String email, String passwordHash, String photo, Gender gender,
                                   LocalDate dob) {
        return new User(null, name, email, passwordHash, photo, Role.USER, gender, dob, null);
    }

    public User withRole(Role newRole) {
        return new User(id, name, email, passwordHash, photo, newRole, gender, dob, createdAt, details);
    }

    public User withPasswordHash(String newHash) {
        return new User(id, name, email, newHash, photo, role, gender, dob, createdAt, details);
    }

    public User withDetails(UserDetails newDetails) {
        return new User(id, name, email, passwordHash, photo, role, gender, dob, createdAt, newDetails);
    }

    public int age(LocalDate today) {
        return dob == null ? 0 : Period.between(dob, today).getYears();
    }
}
```

(Keep any imports the original `User.java` already had; the above is the full file.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=UserDomainTest test`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/user/domain backend/src/test/java/com/sixpack/user
git commit -m "feat(user): add UserDetails, Address, AddressBook and UserStatus domain types"
```

---

### Task 2: Persist the new fields (document, mapping, targeted updates, legacy users)

**Files:**
- Modify: `backend/src/main/java/com/sixpack/user/domain/UserRepository.java`
- Modify: `backend/src/main/java/com/sixpack/user/infra/mongo/UserDocument.java`
- Modify: `backend/src/main/java/com/sixpack/user/infra/mongo/MongoUserRepository.java`
- Test: `backend/src/test/java/com/sixpack/it/UserDetailsPersistenceIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Task 1 types.
- Produces (on `UserRepository`):
  - `void updateProfile(String id, String name, String phone, String locale)` — `$set` only the non-null arguments
  - `void replaceAddresses(String id, List<Address> addresses)`
  - `void touchLastLogin(String id, Instant at)`
  - `void setStatus(String id, UserStatus status)`
  - `save`/`findById` round-trip every `UserDetails` field.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/UserDetailsPersistenceIntegrationTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=UserDetailsPersistenceIntegrationTest test`
Expected: compilation FAIL (`updateProfile`, `replaceAddresses`, `touchLastLogin`, `setStatus` undefined).

- [ ] **Step 3: Write minimal implementation**

`UserRepository.java`: add (with imports `java.time.Instant`, `com.sixpack.user.domain` types are same package):

```java
    /** Sets only the non-null arguments. */
    void updateProfile(String id, String name, String phone, String locale);

    void replaceAddresses(String id, List<Address> addresses);

    void touchLastLogin(String id, Instant at);

    void setStatus(String id, UserStatus status);
```

`UserDocument.java`: add imports `java.util.List` and these fields after `dob`:

```java
    String phone;
    String locale;
    Boolean emailVerified;
    @Indexed
    String status;
    Instant lastLoginAt;
    List<AddressDocument> addresses;

    static class AddressDocument {
        String id;
        String label;
        String name;
        String phone;
        String line1;
        String line2;
        String city;
        String state;
        String country;
        String pinCode;
        boolean isDefault;

        static AddressDocument from(com.sixpack.user.domain.Address a) {
            AddressDocument d = new AddressDocument();
            d.id = a.id();
            d.label = a.label();
            d.name = a.name();
            d.phone = a.phone();
            d.line1 = a.line1();
            d.line2 = a.line2();
            d.city = a.city();
            d.state = a.state();
            d.country = a.country();
            d.pinCode = a.pinCode();
            d.isDefault = a.isDefault();
            return d;
        }

        com.sixpack.user.domain.Address toDomain() {
            return new com.sixpack.user.domain.Address(id, label, name, phone, line1, line2, city, state, country,
                    pinCode, isDefault);
        }
    }
```

`MongoUserRepository.java`: in `save`, after the `doc.dob = ...` line add

```java
        UserDetails d = user.details();
        doc.phone = d.phone();
        doc.locale = d.locale();
        doc.emailVerified = d.emailVerified();
        doc.status = d.status().name();
        doc.lastLoginAt = d.lastLoginAt();
        doc.addresses = d.addresses().stream().map(UserDocument.AddressDocument::from).toList();
```

Replace `toDomain`:

```java
    private static User toDomain(UserDocument d) {
        UserDetails details = new UserDetails(d.phone, d.locale, Boolean.TRUE.equals(d.emailVerified),
                UserStatus.fromStored(d.status), d.lastLoginAt,
                d.addresses == null ? null : d.addresses.stream().map(UserDocument.AddressDocument::toDomain).toList());
        return new User(d.id, d.name, d.email, d.password, d.photo,
                d.role == null ? Role.USER : Role.fromWire(d.role),
                d.gender == null ? null : Gender.fromWire(d.gender),
                d.dob == null ? null : d.dob.toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                d.createdAt, details);
    }
```

Add the four methods:

```java
    @Override
    public void updateProfile(String id, String name, String phone, String locale) {
        Update update = new Update().currentDate("updatedAt");
        if (name != null) {
            update.set("name", name);
        }
        if (phone != null) {
            update.set("phone", phone);
        }
        if (locale != null) {
            update.set("locale", locale);
        }
        mongo.updateFirst(byId(id), update, UserDocument.class);
    }

    @Override
    public void replaceAddresses(String id, List<Address> addresses) {
        mongo.updateFirst(byId(id), new Update().set("addresses",
                addresses.stream().map(UserDocument.AddressDocument::from).toList()).currentDate("updatedAt"),
                UserDocument.class);
    }

    @Override
    public void touchLastLogin(String id, Instant at) {
        mongo.updateFirst(byId(id), new Update().set("lastLoginAt", at), UserDocument.class);
    }

    @Override
    public void setStatus(String id, UserStatus status) {
        mongo.updateFirst(byId(id), new Update().set("status", status.name()).currentDate("updatedAt"),
                UserDocument.class);
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("id").is(id));
    }
```

Add imports: `com.sixpack.user.domain.Address`, `UserDetails`, `UserStatus`, `java.time.Instant`, `org.springframework.data.mongodb.core.query.Update`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=UserDetailsPersistenceIntegrationTest,AuthIntegrationTest,ModularityTests test`
Expected: PASS (new tests plus existing auth tests, proving register/login still work).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(user): persist profile fields and addresses, legacy users load with defaults"
```

---

### Task 3: Profile and address-book endpoints

**Files:**
- Create: `backend/src/main/java/com/sixpack/user/application/ProfileService.java`
- Create: `backend/src/main/java/com/sixpack/user/web/MeController.java`
- Modify: `backend/src/main/java/com/sixpack/user/web/UserView.java`
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/sixpack/it/UserProfileIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Task 2 repository methods, `AddressBook`.
- Produces:
  - `ProfileService`: `User updateProfile(String userId, ProfileCommand c)`, `List<Address> addresses(String userId)`, `Address addAddress(String userId, AddressCommand c)`, `Address updateAddress(String userId, String addressId, AddressCommand c)`, `void deleteAddress(String userId, String addressId)`; `record ProfileCommand(String name, String phone, String locale)`; `record AddressCommand(String label, String name, String phone, String line1, String line2, String city, String state, String country, String pinCode, boolean isDefault)`.
  - HTTP (all 👤, owner only): `PUT /api/v1/user/me` body `{name?, phone?, locale?}` → `{message, user}`; `GET /api/v1/user/me/addresses` → `{addresses}`; `POST` → 201 `{message, address}`; `PUT /me/addresses/{addressId}` → `{message, address}`; `DELETE /me/addresses/{addressId}` → `{message}`. Address JSON: `{id, label, name, phone, line1, line2, city, state, country, pinCode, isDefault}`.
  - `UserView` gains `phone, locale, emailVerified, status, lastLoginAt` (no addresses).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/UserProfileIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class UserProfileIntegrationTest extends IntegrationTest {

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                               String token, Object body) throws Exception {
        req.header("Authorization", bearer(token));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private Map<String, Object> addr(String label, boolean isDefault) {
        return Map.of("label", label, "name", "Asha", "phone", "9876543210", "line1", "12 MG Road",
                "city", "Pune", "state", "MH", "country", "India", "pinCode", "411001", "isDefault", isDefault);
    }

    private String addAddress(String token, String label, boolean isDefault) throws Exception {
        MvcResult res = send(post("/api/v1/user/me/addresses"), token, addr(label, isDefault))
                .andExpect(status().isCreated()).andReturn();
        return body(res).get("address").get("id").asText();
    }

    @Test
    void profileUpdateChangesOnlySentFields() throws Exception {
        String token = register("profile@test.dev");

        send(put("/api/v1/user/me"), token, Map.of("phone", "+919876543210", "locale", "en-IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.phone").value("+919876543210"))
                .andExpect(jsonPath("$.user.locale").value("en-IN"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));
        send(put("/api/v1/user/me"), token, Map.of("name", "New Name"))
                .andExpect(jsonPath("$.user.name").value("New Name"))
                .andExpect(jsonPath("$.user.phone").value("+919876543210"));
    }

    @Test
    void invalidProfileValuesAreBadRequest() throws Exception {
        String token = register("badprofile@test.dev");
        send(put("/api/v1/user/me"), token, Map.of("phone", "abc")).andExpect(status().isBadRequest());
        send(put("/api/v1/user/me"), token, Map.of("locale", "english")).andExpect(status().isBadRequest());
        send(put("/api/v1/user/me"), token, Map.of("name", "x".repeat(81))).andExpect(status().isBadRequest());
    }

    @Test
    void addressBookKeepsExactlyOneDefault() throws Exception {
        String token = register("book@test.dev");
        String first = addAddress(token, "Home", false);
        String second = addAddress(token, "Work", false);
        String third = addAddress(token, "Parents", true);

        send(get("/api/v1/user/me/addresses"), token, null)
                .andExpect(jsonPath("$.addresses.length()").value(3))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(false))
                .andExpect(jsonPath("$.addresses[2].isDefault").value(true));

        send(delete("/api/v1/user/me/addresses/" + third), token, null).andExpect(status().isOk());
        send(get("/api/v1/user/me/addresses"), token, null)
                .andExpect(jsonPath("$.addresses.length()").value(2))
                .andExpect(jsonPath("$.addresses[0].id").value(first))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(true))
                .andExpect(jsonPath("$.addresses[1].isDefault").value(false));

        send(put("/api/v1/user/me/addresses/" + second), token, addr("Work 2", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address.label").value("Work 2"))
                .andExpect(jsonPath("$.address.isDefault").value(true));
        send(get("/api/v1/user/me/addresses"), token, null)
                .andExpect(jsonPath("$.addresses[0].isDefault").value(false))
                .andExpect(jsonPath("$.addresses[1].isDefault").value(true));
    }

    @Test
    void eleventhAddressIsConflict() throws Exception {
        String token = register("many@test.dev");
        for (int i = 0; i < 10; i++) {
            addAddress(token, "A" + i, false);
        }
        send(post("/api/v1/user/me/addresses"), token, addr("A10", false)).andExpect(status().isConflict());
    }

    @Test
    void invalidAddressesUnknownIdsAndAnonymousAreRejected() throws Exception {
        String token = register("rules@test.dev");
        Map<String, Object> noLine1 = new java.util.HashMap<>(addr("X", false));
        noLine1.remove("line1");
        send(post("/api/v1/user/me/addresses"), token, noLine1).andExpect(status().isBadRequest());
        Map<String, Object> badPin = new java.util.HashMap<>(addr("X", false));
        badPin.put("pinCode", "12");
        send(post("/api/v1/user/me/addresses"), token, badPin).andExpect(status().isBadRequest());
        Map<String, Object> badPhone = new java.util.HashMap<>(addr("X", false));
        badPhone.put("phone", "call me");
        send(post("/api/v1/user/me/addresses"), token, badPhone).andExpect(status().isBadRequest());

        send(put("/api/v1/user/me/addresses/missing"), token, addr("X", false)).andExpect(status().isNotFound());
        send(delete("/api/v1/user/me/addresses/missing"), token, null).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/user/me/addresses")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/user/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersNeverSeeEachOthersAddresses() throws Exception {
        String alice = register("alice@test.dev");
        String bob = register("bob@test.dev");
        String aliceAddress = addAddress(alice, "Home", true);

        send(get("/api/v1/user/me/addresses"), bob, null).andExpect(jsonPath("$.addresses.length()").value(0));
        send(delete("/api/v1/user/me/addresses/" + aliceAddress), bob, null).andExpect(status().isNotFound());
        send(get("/api/v1/user/me/addresses"), alice, null).andExpect(jsonPath("$.addresses.length()").value(1));
    }

    @Test
    void userViewDoesNotExposeAddresses() throws Exception {
        String token = register("view@test.dev");
        addAddress(token, "Home", true);
        send(get("/api/v1/user/me"), token, null).andExpect(jsonPath("$.user.addresses").doesNotExist());
    }

    @Test
    void legacyUserCanSaveAnAddress() throws Exception {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("name", "Old")
                .append("email", "legacy@test.dev").append("password", encoder.encode("password123"))
                .append("photo", "").append("role", "user").append("dob", new java.util.Date(0)));
        MvcResult login = mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "legacy@test.dev",
                                "password", "password123"))))
                .andExpect(status().isOk()).andReturn();
        String token = body(login).get("token").asText();

        String addressId = addAddress(token, "Home", false);

        assertThat(users.findById(id.toHexString()).orElseThrow().details().addresses())
                .extracting(a -> a.id()).containsExactly(addressId);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=UserProfileIntegrationTest test`
Expected: FAIL (404/403 on `/api/v1/user/me/addresses`; `PUT /me` hits the admin rule).

- [ ] **Step 3: Write minimal implementation**

`ProfileService.java`:

```java
package com.sixpack.user.application;

import com.sixpack.shared.ApiException;
import com.sixpack.user.domain.Address;
import com.sixpack.user.domain.AddressBook;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Self-service profile and address book. Every method acts on the caller's own account only. */
@Service
public class ProfileService {

    public record ProfileCommand(String name, String phone, String locale) {
    }

    public record AddressCommand(String label, String name, String phone, String line1, String line2, String city,
                                 String state, String country, String pinCode, boolean isDefault) {
        Address toAddress(String id) {
            return new Address(id, trimOrNull(label), name.trim(), phone.trim(), line1.trim(), trimOrNull(line2),
                    city.trim(), state.trim(), country.trim(), pinCode.trim(), isDefault);
        }
    }

    private final UserRepository users;

    ProfileService(UserRepository users) {
        this.users = users;
    }

    public User updateProfile(String userId, ProfileCommand c) {
        user(userId);
        users.updateProfile(userId, trimOrNull(c.name()), trimOrNull(c.phone()), trimOrNull(c.locale()));
        return user(userId);
    }

    public List<Address> addresses(String userId) {
        return user(userId).details().addresses();
    }

    public Address addAddress(String userId, AddressCommand c) {
        Address created = c.toAddress(UUID.randomUUID().toString());
        users.replaceAddresses(userId, AddressBook.add(addresses(userId), created));
        return created;
    }

    public Address updateAddress(String userId, String addressId, AddressCommand c) {
        List<Address> next = AddressBook.update(addresses(userId), addressId, c.toAddress(addressId));
        users.replaceAddresses(userId, next);
        return next.stream().filter(a -> a.id().equals(addressId)).findFirst().orElseThrow();
    }

    public void deleteAddress(String userId, String addressId) {
        users.replaceAddresses(userId, AddressBook.remove(addresses(userId), addressId));
    }

    private User user(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

`web/MeController.java`:

```java
package com.sixpack.user.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.user.application.ProfileService;
import com.sixpack.user.application.ProfileService.AddressCommand;
import com.sixpack.user.application.ProfileService.ProfileCommand;
import com.sixpack.user.domain.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own profile and address book. */
@Validated
@RestController
@RequestMapping("/api/v1/user/me")
class MeController {

    static final String PHONE = "^\\+?[0-9]{10,13}$";

    record ProfileRequest(
            @Size(max = 80) String name,
            @Pattern(regexp = "^\\+?[0-9]{10,13}$|", message = "must be 10-13 digits, optionally starting with +")
            String phone,
            @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$|", message = "must look like en or en-IN") String locale) {
    }

    record AddressRequest(
            @Size(max = 30) String label,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = PHONE, message = "must be 10-13 digits, optionally starting with +")
            String phone,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @NotBlank @Size(max = 60) String country,
            @NotBlank @Pattern(regexp = "\\d{4,10}", message = "must be 4-10 digits") String pinCode,
            @JsonProperty("isDefault") boolean isDefault) {
        AddressCommand toCommand() {
            return new AddressCommand(label, name, phone, line1, line2, city, state, country, pinCode, isDefault);
        }
    }

    record AddressView(String id, String label, String name, String phone, String line1, String line2, String city,
                       String state, String country, String pinCode, @JsonProperty("isDefault") boolean isDefault) {
        static AddressView from(Address a) {
            return new AddressView(a.id(), a.label(), a.name(), a.phone(), a.line1(), a.line2(), a.city(),
                    a.state(), a.country(), a.pinCode(), a.isDefault());
        }
    }

    private final ProfileService profile;

    MeController(ProfileService profile) {
        this.profile = profile;
    }

    @PutMapping
    Map<String, Object> update(@Valid @RequestBody ProfileRequest request, @AuthenticationPrincipal Jwt jwt) {
        var user = profile.updateProfile(AuthUser.from(jwt).id(),
                new ProfileCommand(request.name(), request.phone(), request.locale()));
        return ApiResponse.of("message", "Profile updated successfully", "user", UserView.from(user));
    }

    @GetMapping("/addresses")
    Map<String, Object> addresses(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of("addresses",
                profile.addresses(AuthUser.from(jwt).id()).stream().map(AddressView::from).toList());
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> add(@Valid @RequestBody AddressRequest request, @AuthenticationPrincipal Jwt jwt) {
        var created = profile.addAddress(AuthUser.from(jwt).id(), request.toCommand());
        return ApiResponse.of("message", "Address saved", "address", AddressView.from(created));
    }

    @PutMapping("/addresses/{addressId}")
    Map<String, Object> update(@PathVariable String addressId, @Valid @RequestBody AddressRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        var updated = profile.updateAddress(AuthUser.from(jwt).id(), addressId, request.toCommand());
        return ApiResponse.of("message", "Address updated", "address", AddressView.from(updated));
    }

    @DeleteMapping("/addresses/{addressId}")
    Map<String, Object> delete(@PathVariable String addressId, @AuthenticationPrincipal Jwt jwt) {
        profile.deleteAddress(AuthUser.from(jwt).id(), addressId);
        return ApiResponse.message("Address deleted");
    }
}
```

(Import `com.sixpack.shared.security.AuthUser` exactly as `AuthController` does; copy its import line if the package differs.)

`UserView.java`: add components `String phone, String locale, boolean emailVerified, String status, Instant lastLoginAt` after `createdAt`, and in `from(User u)` pass `u.details().phone(), u.details().locale(), u.details().emailVerified(), u.details().status().name(), u.details().lastLoginAt()`.

`SecurityConfig.java`: replace the two lines `.requestMatchers(GET, "/api/v1/user/me").authenticated()` and `.requestMatchers(DELETE, "/api/v1/user/me").authenticated()` with

```java
                        .requestMatchers("/api/v1/user/me", "/api/v1/user/me/**").authenticated()
```

(It must stay above `.requestMatchers("/api/v1/user/**").hasRole(ADMIN)`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=UserProfileIntegrationTest,AuthIntegrationTest,AuthorizationIntegrationTest,ConsentIntegrationTest,ModularityTests test`
Expected: PASS. If `isDefault` serialises as `default`, confirm the `@JsonProperty("isDefault")` annotations are on the record components.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(user): self-service profile and address book endpoints"
```

---

### Task 4: Account status (suspend/reactivate) and last-login stamp

**Files:**
- Modify: `backend/src/main/java/com/sixpack/user/application/AuthService.java` (`login`, `me`)
- Modify: `backend/src/main/java/com/sixpack/user/application/UserAdminService.java` (`changeStatus`)
- Modify: `backend/src/main/java/com/sixpack/user/web/UserAdminController.java` (`update`)
- Test: `backend/src/test/java/com/sixpack/it/UserStatusIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `UserRepository.setStatus/touchLastLogin`, `UserStatus`.
- Produces: `UserAdminService.changeStatus(String id, UserStatus status, String actingAdminId)`. `PUT /api/v1/user/{id}` now takes `{role?, status?}` (at least one; role was previously required, so this only relaxes the contract). Login for a suspended user returns 403 `Your account is suspended` (after the password check). `GET /user/me` for a suspended user returns 403. Successful login stamps `lastLoginAt`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/UserStatusIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UserStatusIntegrationTest extends IntegrationTest {

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions updateUser(String admin, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/user/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void suspendedUsersCannotLogInOrUseTheirSession() throws Exception {
        String admin = adminToken();
        String token = register("sus@test.dev");
        String id = userId(token);

        updateUser(admin, id, Map.of("status", "suspended")).andExpect(status().isOk());

        login("sus@test.dev", "password123").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        // Status is only revealed after the password is right.
        login("sus@test.dev", "wrong-password").andExpect(status().isUnauthorized());

        updateUser(admin, id, Map.of("status", "ACTIVE")).andExpect(status().isOk());
        login("sus@test.dev", "password123").andExpect(status().isOk());
    }

    @Test
    void adminCannotSuspendThemselvesAndStatusMustBeValid() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);

        updateUser(admin, adminId, Map.of("status", "suspended")).andExpect(status().isBadRequest());
        updateUser(admin, adminId, Map.of("status", "banned")).andExpect(status().isBadRequest());
        updateUser(admin, adminId, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void roleOnlyUpdateStillWorks() throws Exception {
        String admin = adminToken();
        String id = userId(register("role@test.dev"));

        updateUser(admin, id, Map.of("role", "admin")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.user.role").value("admin"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));
    }

    @Test
    void loginStampsLastLogin() throws Exception {
        String admin = adminToken();
        String id = userId(register("seen@test.dev"));
        mvc.perform(get("/api/v1/user/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.user.lastLoginAt").doesNotExist());

        login("seen@test.dev", "password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.lastLoginAt").exists());

        mvc.perform(get("/api/v1/user/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.user.lastLoginAt").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=UserStatusIntegrationTest test`
Expected: FAIL (400 on `{"status":...}` because `role` is `@NotBlank`; suspended users still log in; no `lastLoginAt`).

- [ ] **Step 3: Write minimal implementation**

`AuthService.login`: after the password check and before the success counter:

```java
        if (user.details().status() == UserStatus.SUSPENDED) {
            meters.counter("sixpack.auth.login", "result", "suspended").increment();
            throw ApiException.forbidden("Your account is suspended");
        }
        users.touchLastLogin(user.id(), Instant.now());
        user = users.findById(user.id()).orElse(user);
```

(`user` must be non-final in `login`; change `User user = ...` to a plain local and use it for the final `new AuthResult(issue(user), user)`.) Add imports `java.time.Instant`, `com.sixpack.user.domain.UserStatus`.

`AuthService.me`:

```java
    public User me(String userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (user.details().status() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("Your account is suspended");
        }
        return user;
    }
```

`UserAdminService`:

```java
    public void changeStatus(String id, UserStatus status, String actingAdminId) {
        if (id.equals(actingAdminId) && status != UserStatus.ACTIVE) {
            throw ApiException.badRequest("You cannot suspend your own account");
        }
        get(id);
        users.setStatus(id, status);
    }
```

`UserAdminController`: replace `UpdateRoleRequest` and `update` with

```java
    record UpdateUserRequest(
            @Pattern(regexp = "(?i)admin|user|", message = "must be admin or user") String role,
            @Pattern(regexp = "(?i)active|suspended|", message = "must be active or suspended") String status) {
    }
```

```java
    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        boolean hasRole = request.role() != null && !request.role().isBlank();
        boolean hasStatus = request.status() != null && !request.status().isBlank();
        if (!hasRole && !hasStatus) {
            throw ApiException.badRequest("role or status: at least one is required");
        }
        String actingAdminId = AuthUser.from(jwt).id();
        if (hasRole) {
            service.changeRole(id, Role.fromWire(request.role()), actingAdminId);
        }
        if (hasStatus) {
            service.changeStatus(id, UserStatus.parse(request.status()), actingAdminId);
        }
        return ApiResponse.message("User updated successfully");
    }
```

Add imports `com.sixpack.shared.ApiException` and `com.sixpack.user.domain.UserStatus` where missing.

Known limit (document in Task 6): suspension is enforced at login and `/me`; an already-issued JWT keeps working until it expires (24 h, ADR-006). Per-request enforcement would cost a DB read per call and is out of scope.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=UserStatusIntegrationTest,UserProfileIntegrationTest,AuthIntegrationTest,AuthorizationIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(user): account suspension and last-login stamp"
```

---

### Task 5: Frontend — address book page, checkout picker, data export

**Files:**
- Modify: `ecommerce-frontend/src/types/types.ts`, `ecommerce-frontend/src/types/api-types.ts`
- Modify: `ecommerce-frontend/src/redux/api/userAPI.ts`
- Create: `ecommerce-frontend/src/pages/account/addresses.tsx`
- Modify: `ecommerce-frontend/src/App.tsx` (lazy import + route), `ecommerce-frontend/src/components/accountMenu.tsx` (link)
- Modify: `ecommerce-frontend/src/pages/shipping.tsx` (saved-address picker), `ecommerce-frontend/src/pages/account/privacy.tsx` (export)
- Modify: `ecommerce-frontend/src/styles/_shipping.scss` (small additions)

**Interfaces:**
- Consumes: backend endpoints from Task 3.
- Produces: `SavedAddress` type; hooks `useGetMyAddressesQuery`, `useLazyGetMyAddressesQuery`, `useAddAddressMutation`, `useUpdateAddressMutation`, `useDeleteAddressMutation`, `useUpdateMyProfileMutation`.

- [ ] **Step 1: Types and API**

`types.ts`: extend `User`:

```ts
  phone?: string;
  locale?: string;
  emailVerified?: boolean;
  status?: "ACTIVE" | "SUSPENDED";
  lastLoginAt?: string;
```

and add

```ts
export type SavedAddress = {
  id: string;
  label?: string;
  name: string;
  phone: string;
  line1: string;
  line2?: string;
  city: string;
  state: string;
  country: string;
  pinCode: string;
  isDefault: boolean;
};

export type SavedAddressInput = Omit<SavedAddress, "id">;
```

`api-types.ts`: add `SavedAddress` to the import from `./types` and

```ts
export type AddressesResponse = { success: boolean; addresses: SavedAddress[] };
export type AddressResponse = { success: boolean; message: string; address: SavedAddress };
```

`userAPI.ts`: import `AddressesResponse, AddressResponse` and `SavedAddressInput`; change `tagTypes: ["users"]` to `["users", "addresses"]`; add endpoints:

```ts
    updateMyProfile: builder.mutation<UserResponse & MessageResponse, { name?: string; phone?: string; locale?: string }>({
      query: (body) => ({ url: "me", method: "PUT", body }),
      invalidatesTags: ["users"],
    }),
    getMyAddresses: builder.query<AddressesResponse, void>({
      query: () => "me/addresses",
      providesTags: ["addresses"],
    }),
    addAddress: builder.mutation<AddressResponse, SavedAddressInput>({
      query: (body) => ({ url: "me/addresses", method: "POST", body }),
      invalidatesTags: ["addresses"],
    }),
    updateAddress: builder.mutation<AddressResponse, { id: string; body: SavedAddressInput }>({
      query: ({ id, body }) => ({ url: `me/addresses/${id}`, method: "PUT", body }),
      invalidatesTags: ["addresses"],
    }),
    deleteAddress: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: `me/addresses/${id}`, method: "DELETE" }),
      invalidatesTags: ["addresses"],
    }),
```

and export `useUpdateMyProfileMutation, useGetMyAddressesQuery, useLazyGetMyAddressesQuery, useAddAddressMutation, useUpdateAddressMutation, useDeleteAddressMutation` in the destructured export.

- [ ] **Step 2: Address book page**

Create `ecommerce-frontend/src/pages/account/addresses.tsx`:

```tsx
import { ChangeEvent, FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuArrowLeft, LuMapPin, LuPencil, LuTrash2 } from "react-icons/lu";
import { Link } from "react-router-dom";
import { errorMessage } from "../../redux/api/baseQuery";
import {
  useAddAddressMutation,
  useDeleteAddressMutation,
  useGetMyAddressesQuery,
  useUpdateAddressMutation,
} from "../../redux/api/userAPI";
import { SavedAddress, SavedAddressInput } from "../../types/types";

const empty: SavedAddressInput = {
  label: "",
  name: "",
  phone: "",
  line1: "",
  line2: "",
  city: "",
  state: "",
  country: "India",
  pinCode: "",
  isDefault: false,
};

const MAX_ADDRESSES = 10;

/** Saved delivery addresses: list, add, edit, delete, and choose the default. */
const Addresses = () => {
  const { data, isLoading } = useGetMyAddressesQuery();
  const [addAddress, { isLoading: adding }] = useAddAddressMutation();
  const [updateAddress, { isLoading: updating }] = useUpdateAddressMutation();
  const [deleteAddress] = useDeleteAddressMutation();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<SavedAddressInput>(empty);
  const addresses = data?.addresses ?? [];

  const change = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value, type, checked } = e.target as HTMLInputElement;
    setForm((prev) => ({ ...prev, [name]: type === "checkbox" ? checked : value }));
  };

  const edit = (a: SavedAddress) => {
    const { id, ...rest } = a;
    setEditingId(id);
    setForm({ ...empty, ...rest, label: rest.label ?? "", line2: rest.line2 ?? "" });
  };

  const reset = () => {
    setEditingId(null);
    setForm(empty);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = editingId ? await updateAddress({ id: editingId, body: form }) : await addAddress(form);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    reset();
  };

  const remove = async (id: string) => {
    if (!confirm("Delete this address?")) return;
    const res = await deleteAddress(id);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    if (editingId === id) reset();
  };

  return (
    <div className="shipping address-book">
      <Link className="back-btn" to="/">
        <LuArrowLeft /> Back to shop
      </Link>
      <h1>Saved addresses</h1>
      <p>
        {addresses.length} of {MAX_ADDRESSES} saved. Your default address is pre-selected at checkout.
      </p>

      {isLoading ? null : addresses.length === 0 ? (
        <p className="address-book__empty">You have no saved addresses yet.</p>
      ) : (
        <ul className="address-book__list">
          {addresses.map((a) => (
            <li key={a.id}>
              <LuMapPin />
              <div>
                <strong>
                  {a.label || a.name} {a.isDefault && <span className="badge badge--delivered">Default</span>}
                </strong>
                <span>
                  {a.line1}
                  {a.line2 ? `, ${a.line2}` : ""}, {a.city}, {a.state} {a.pinCode}, {a.country}
                </span>
                <small>
                  {a.name} · {a.phone}
                </small>
              </div>
              <button type="button" aria-label="Edit address" onClick={() => edit(a)}>
                <LuPencil />
              </button>
              <button type="button" aria-label="Delete address" onClick={() => remove(a.id)}>
                <LuTrash2 />
              </button>
            </li>
          ))}
        </ul>
      )}

      {(editingId || addresses.length < MAX_ADDRESSES) && (
        <form onSubmit={submit}>
          <h2>{editingId ? "Edit address" : "Add an address"}</h2>
          <label>
            Label (optional)
            <input name="label" value={form.label} onChange={change} maxLength={30} placeholder="Home, Work…" />
          </label>
          <label>
            Full name
            <input name="name" value={form.name} onChange={change} maxLength={80} required autoComplete="name" />
          </label>
          <label>
            Phone
            <input name="phone" value={form.phone} onChange={change} required autoComplete="tel"
                   pattern="\+?[0-9]{10,13}" title="10-13 digits, optionally starting with +" />
          </label>
          <label className="span-2">
            Address
            <input name="line1" value={form.line1} onChange={change} maxLength={200} required
                   placeholder="House no., street, area" autoComplete="address-line1" />
          </label>
          <label className="span-2">
            Landmark / apartment (optional)
            <input name="line2" value={form.line2} onChange={change} maxLength={200} autoComplete="address-line2" />
          </label>
          <label>
            City
            <input name="city" value={form.city} onChange={change} maxLength={80} required />
          </label>
          <label>
            State
            <input name="state" value={form.state} onChange={change} maxLength={80} required />
          </label>
          <label>
            Country
            <select name="country" value={form.country} onChange={change} required>
              <option value="India">India</option>
            </select>
          </label>
          <label>
            Pin code
            <input name="pinCode" value={form.pinCode} onChange={change} required inputMode="numeric"
                   pattern="\d{4,10}" title="4-10 digits" />
          </label>
          <label className="span-2 address-book__default">
            <input type="checkbox" name="isDefault" checked={form.isDefault} onChange={change} /> Make this my
            default address
          </label>
          <button type="submit" className="span-2" disabled={adding || updating}>
            {editingId ? "Save changes" : "Save address"}
          </button>
          {editingId && (
            <button type="button" className="span-2 address-book__cancel" onClick={reset}>
              Cancel
            </button>
          )}
        </form>
      )}
    </div>
  );
};

export default Addresses;
```

Append to `ecommerce-frontend/src/styles/_shipping.scss` (use the token variables that file already uses for colours and spacing; if it has none, fall back to `var(--border)`-style tokens from `_tokens.scss`):

```scss
.address-book {
  &__list {
    list-style: none;
    padding: 0;
    margin: 0 0 1.5rem;
    display: grid;
    gap: 0.75rem;

    li {
      display: grid;
      grid-template-columns: auto 1fr auto auto;
      align-items: start;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
      border: 1px solid var(--border, #ddd);
      border-radius: 0.5rem;

      div {
        display: grid;
        gap: 0.15rem;
      }

      button {
        background: none;
        border: 0;
        cursor: pointer;
        padding: 0.25rem;
      }
    }
  }

  &__empty {
    margin-bottom: 1.5rem;
  }

  &__default {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }

  &__cancel {
    background: transparent;
  }
}
```

- [ ] **Step 3: Route, menu link, checkout picker, export**

`App.tsx`: add `const Addresses = lazy(() => import("./pages/account/addresses"));` next to `PrivacySettings`, and inside the authenticated route group: `<Route path="/account/addresses" element={<Addresses />} />`.

`accountMenu.tsx`: import `LuMapPin` from `react-icons/lu` and add, before the "Privacy & data" link:

```tsx
        <Link onClick={onClose} to={"/account/addresses"}>
          <LuMapPin />
          Saved addresses
        </Link>
```

`shipping.tsx`: import `useGetMyAddressesQuery` from `../redux/api/userAPI` and `SavedAddress` from `../types/types`; inside the component add

```tsx
  const { data: book } = useGetMyAddressesQuery();
  const [picked, setPicked] = useState<string>("");
  const savedAddresses = book?.addresses ?? [];

  const applySaved = (a: SavedAddress) => {
    setPicked(a.id);
    setShippingInfo({
      address: a.line2 ? `${a.line1}, ${a.line2}` : a.line1,
      city: a.city,
      state: a.state,
      country: a.country,
      pinCode: a.pinCode,
    });
  };

  // Pre-select the default address once, unless the user already typed something.
  useEffect(() => {
    const def = savedAddresses.find((a) => a.isDefault);
    if (def && !picked && !saved.address) applySaved(def);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [book]);
```

and, above the "Address" `<label>` inside the `<form>`:

```tsx
        {savedAddresses.length > 0 && (
          <label className="span-2">
            Saved addresses
            <select value={picked} onChange={(e) => {
              const a = savedAddresses.find((x) => x.id === e.target.value);
              if (a) applySaved(a);
              else setPicked("");
            }}>
              <option value="">Enter a new address</option>
              {savedAddresses.map((a) => (
                <option key={a.id} value={a.id}>
                  {(a.label || a.name)} — {a.line1}, {a.city}
                </option>
              ))}
            </select>
          </label>
        )}
```

(`saved` is the existing `shippingInfo: saved` selector alias in this component. If ESLint rejects the disable comment, restructure the effect into a `useMemo`-free early return instead; the lint gate must pass without new warnings.)

`privacy.tsx`: import `useLazyGetMyAddressesQuery` from `../../redux/api/userAPI`; add `const [fetchAddresses] = useLazyGetMyAddressesQuery();`; in `exportData` replace the `orders`/`data` lines with

```tsx
      const [orders, addresses] = await Promise.all([fetchOrders().unwrap(), fetchAddresses().unwrap()]);
      const data = {
        exportedAt: new Date().toISOString(),
        profile: user,
        addresses: addresses.addresses,
        orders: orders.orders,
        consentHistory: consent?.history ?? [],
      };
```

- [ ] **Step 4: Verify**

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both succeed with no type errors or warnings. Then, with the stack up (`docker compose up -d --build`): sign in, add two addresses at `/account/addresses`, set the second as default, go through cart → shipping and confirm the default is pre-selected and switching the dropdown fills the form; open Privacy & data → Download my data and confirm the JSON contains `addresses`.

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(account): saved address book, checkout address picker and data export"
```

---

### Task 6: Docs and full verification

**Files:**
- Modify: `docs/foundations/contracts.md`, `requirements.md`, `architecture.md`, `compliance-consent.md`, `test-rollout.md`

- [ ] **Step 1: Contracts**

In `contracts.md` under **User — `/user`**:
- `PUT /{id}` row: request becomes `{role?, status?}` (at least one; `status` is `active|suspended`), 400 if you suspend yourself or send neither.
- `POST /login` row: add "403 if the account is suspended (checked after the password). A successful login stamps `lastLoginAt`".
- `GET /me` row: add "403 if suspended".
- Add rows: `PUT /me` 👤 `{name?, phone?, locale?}` → `{success, message, user}` (400 on invalid phone/locale); `GET /me/addresses` 👤; `POST /me/addresses` 👤 (201, 409 beyond 10, 400 invalid); `PUT /me/addresses/{addressId}` 👤 (404 unknown); `DELETE /me/addresses/{addressId}` 👤 (404 unknown, the first remaining address becomes default).
- Update the shape line to `user = {_id, name, email, photo, role, gender?, dob, age, createdAt, phone?, locale?, emailVerified, status, lastLoginAt?}` and add `address = {id, label?, name, phone, line1, line2?, city, state, country, pinCode, isDefault}` with the rules: at most 10, exactly one default when any exist, addresses are never part of `user`.

- [ ] **Step 2: Requirements, ADR, compliance, rollout**

- `requirements.md`: add `FR8a` (profile fields and suspend/reactivate by admin) and `FR8b` (saved addresses with default, checkout picker).
- `architecture.md`: add `ADR-016` dated 2026-10-17 above ADR-015: nested `UserDetails`; targeted `$set` updates so profile/address/login writes don't overwrite concurrent changes; `AddressBook` as pure domain; marketing preferences deliberately stay in the consent ledger; suspension enforced at login and `/me` only (JWT stays valid until its 24 h expiry, per-request checks rejected as a DB read per call); `emailVerified` is stored but nothing sets it yet (no verification flow). Rejected: a separate `addresses` collection (always read with the user, max 10) and a global suspended-user filter.
- `compliance-consent.md`: in the retention schedule (§7) add a row for phone, locale, saved addresses and `lastLoginAt`: kept for the life of the account, erased with it (`DELETE /user/me` removes the embedded fields); note that the data export now includes addresses and that none of these fields are used for marketing.
- `test-rollout.md`: list `UserDetailsPersistenceIntegrationTest`, `UserProfileIntegrationTest`, `UserStatusIntegrationTest`, `UserDomainTest`; rollout needs no migration (new fields are optional; a `status` index is created at startup).

- [ ] **Step 3: Full verification**

```bash
cd backend && mvn verify
cd ../ecommerce-frontend && npm run build && npm run lint
```

Expected: all green, including `ModularityTests` and every pre-existing integration test.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: document profile fields, address book and ADR-016"
```

---

## Self-Review

- **Spec coverage (§4 User + Address rows, Tier 1):** `phone, emailVerified, status, lastLoginAt, addresses[], locale` → Tasks 1–4; `Address{label, name, phone, line1, line2, city, state, country, pinCode, isDefault}` → Tasks 1, 3; admin-visible effect of `status` → Task 4; address book UI and checkout picker → Task 5; docs and compliance → Task 6. Deliberately not here: "marketing prefs" (already the consent ledger, ruled out above), email verification flow (field only), loyalty points and GSTIN/geo (Tier 2), orders consuming addresses (plan 3), an admin UI for suspend (API only).
- **Placeholder scan:** none; every code step has code. The two conditional notes (the `AuthUser` import line and the SCSS token fallback) name the exact file to copy from.
- **Type consistency:** `UserDetails` has 6 components (Task 1) matching `MongoUserRepository.toDomain`, `UserView.from`, and the persistence test. `Address` has 11 components everywhere (domain, `AddressDocument`, `AddressCommand` maps onto it, `AddressView`). `ProfileService.AddressCommand` field order matches `AddressRequest.toCommand()`. Repository method names (`updateProfile`, `replaceAddresses`, `touchLastLogin`, `setStatus`) are identical in Tasks 2, 3 and 4.
- **Review Focus coverage:** 1 → Task 2 `legacyDocumentWithoutNewFieldsLoadsWithDefaults` and Task 3 `legacyUserCanSaveAnAddress`; 2 → Task 1 unit tests and Task 3 `addressBookKeepsExactlyOneDefault`; 3 → Task 3 `eleventhAddressIsConflict`, `invalidAddressesUnknownIdsAndAnonymousAreRejected`; 4 → Task 3 `usersNeverSeeEachOthersAddresses` and the anonymous 401 checks; 5 → Task 4 `suspendedUsersCannotLogInOrUseTheirSession`, `adminCannotSuspendThemselves…`.
