package com.sixpack.user.application;

import com.sixpack.user.UserDirectory;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;

    UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    public Map<String, String> namesByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::id, User::name, (a, b) -> a));
    }
}
