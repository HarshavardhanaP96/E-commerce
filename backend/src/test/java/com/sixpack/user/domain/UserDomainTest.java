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
