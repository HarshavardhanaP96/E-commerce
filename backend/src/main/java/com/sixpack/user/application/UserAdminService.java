package com.sixpack.user.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.shared.ApiException;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.UserStatus;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class UserAdminService {

    private final UserRepository users;
    private final AccountService accounts;
    private final AuditTrail audit;

    UserAdminService(UserRepository users, AccountService accounts, AuditTrail audit) {
        this.users = users;
        this.accounts = accounts;
        this.audit = audit;
    }

    public List<User> list(Role role, Gender gender) {
        return users.findAll(role, gender);
    }

    public User get(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public void changeRole(String id, Role role, String actingAdminId) {
        if (id.equals(actingAdminId) && role != Role.ADMIN) {
            throw ApiException.badRequest("You cannot remove your own admin role");
        }
        User before = get(id);
        users.save(before.withRole(role));
        audit.record(AuditEvent.of(AuditAction.USER_ROLE_CHANGED).on("user", id)
                .changing(Changes.diff(Map.of("role", before.role().name()), Map.of("role", role.name()),
                        UserAudit.ROLE)));
    }

    public void changeStatus(String id, UserStatus status, String actingAdminId) {
        if (id.equals(actingAdminId) && status != UserStatus.ACTIVE) {
            throw ApiException.badRequest("You cannot suspend your own account");
        }
        User before = get(id);
        users.setStatus(id, status);
        audit.record(AuditEvent.of(AuditAction.USER_STATUS_CHANGED).on("user", id)
                .changing(Changes.diff(Map.of("status", before.details().status().name()),
                        Map.of("status", status.name()), UserAudit.STATUS)));
    }

    public void delete(String id, String actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        accounts.erase(get(id));
        audit.record(AuditEvent.of(AuditAction.USER_ERASED).on("user", id));
    }
}
