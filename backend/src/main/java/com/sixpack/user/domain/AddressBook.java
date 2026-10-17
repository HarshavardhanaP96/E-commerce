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
