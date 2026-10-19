package com.sixpack.user.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.consent.ConsentLedger;
import com.sixpack.shared.ApiException;
import com.sixpack.storage.FileStorage;
import com.sixpack.user.AccountDeletionGuard;
import com.sixpack.user.UserDeletedEvent;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Account erasure (DPDP right to erasure), used both by the account owner and by admins. */
@Service
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final FileStorage storage;
    private final List<AccountDeletionGuard> guards;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final UserAccountAccess access;
    private final ConsentLedger consents;

    AccountService(UserRepository users, PasswordEncoder encoder, FileStorage storage,
                   List<AccountDeletionGuard> guards, ApplicationEventPublisher events, AuditTrail audit,
                   UserAccountAccess access, ConsentLedger consents) {
        this.users = users;
        this.encoder = encoder;
        this.storage = storage;
        this.guards = guards;
        this.events = events;
        this.audit = audit;
        this.access = access;
        this.consents = consents;
    }

    public void deleteOwnAccount(String userId, String password) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (user.role() == Role.ADMIN) {
            throw ApiException.badRequest("Admin accounts can't be deleted here. Ask another admin to remove it.");
        }
        if (user.passwordHash() == null || !encoder.matches(password, user.passwordHash())) {
            throw ApiException.forbidden("Password is incorrect");
        }
        erase(user);
        audit.record(AuditEvent.of(AuditAction.ACCOUNT_DELETED_SELF).on("user", userId));
    }

    void erase(User user) {
        for (AccountDeletionGuard guard : guards) {
            guard.blockReason(user.id()).ifPresent(reason -> {
                throw ApiException.conflict(reason);
            });
        }
        storage.delete(user.photo());
        users.deleteById(user.id());
        access.evict(user.id());
        consents.startRetentionClock(user.id());
        events.publishEvent(new UserDeletedEvent(user.id()));
    }
}
