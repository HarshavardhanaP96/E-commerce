package com.sixpack.user.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
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
    private final AuditTrail audit;

    ProfileService(UserRepository users, AuditTrail audit) {
        this.users = users;
        this.audit = audit;
    }

    public User updateProfile(String userId, ProfileCommand c) {
        User before = user(userId);
        users.updateProfile(userId, trimOrNull(c.name()), trimOrNull(c.phone()), trimOrNull(c.locale()));
        User after = user(userId);
        audit.record(AuditEvent.of(AuditAction.PROFILE_UPDATED).on("user", userId)
                .changing(Changes.diff(UserAudit.profile(before), UserAudit.profile(after), UserAudit.PROFILE)));
        return after;
    }

    public List<Address> addresses(String userId) {
        return user(userId).details().addresses();
    }

    public Address addAddress(String userId, AddressCommand c) {
        Address created = c.toAddress(UUID.randomUUID().toString());
        users.replaceAddresses(userId, AddressBook.add(addresses(userId), created));
        audit.record(AuditEvent.of(AuditAction.ADDRESS_ADDED).on("address", created.id()));
        return created;
    }

    public Address updateAddress(String userId, String addressId, AddressCommand c) {
        List<Address> next = AddressBook.update(addresses(userId), addressId, c.toAddress(addressId));
        users.replaceAddresses(userId, next);
        audit.record(AuditEvent.of(AuditAction.ADDRESS_UPDATED).on("address", addressId));
        return next.stream().filter(a -> a.id().equals(addressId)).findFirst().orElseThrow();
    }

    public void deleteAddress(String userId, String addressId) {
        users.replaceAddresses(userId, AddressBook.remove(addresses(userId), addressId));
        audit.record(AuditEvent.of(AuditAction.ADDRESS_DELETED).on("address", addressId));
    }

    private User user(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
