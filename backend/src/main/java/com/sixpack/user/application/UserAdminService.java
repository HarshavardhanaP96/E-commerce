package com.sixpack.user.application;

import com.sixpack.shared.ApiException;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.UserStatus;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserAdminService {

    private final UserRepository users;
    private final AccountService accounts;

    UserAdminService(UserRepository users, AccountService accounts) {
        this.users = users;
        this.accounts = accounts;
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
        users.save(get(id).withRole(role));
    }

    public void changeStatus(String id, UserStatus status, String actingAdminId) {
        if (id.equals(actingAdminId) && status != UserStatus.ACTIVE) {
            throw ApiException.badRequest("You cannot suspend your own account");
        }
        get(id);
        users.setStatus(id, status);
    }

    public void delete(String id, String actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        accounts.erase(get(id));
    }
}
