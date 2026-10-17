package com.sixpack.user.infra.mongo;

import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoUserRepository implements UserRepository {

    private final MongoTemplate mongo;

    MongoUserRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, UserDocument.class)).map(MongoUserRepository::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("email").is(email)), UserDocument.class))
                .map(MongoUserRepository::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return mongo.exists(Query.query(Criteria.where("email").is(email)), UserDocument.class);
    }

    @Override
    public List<User> findAll(Role role, Gender gender) {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (role != null) {
            query.addCriteria(Criteria.where("role").is(role.wireValue()));
        }
        if (gender != null) {
            query.addCriteria(Criteria.where("gender").is(gender.wireValue()));
        }
        return mongo.find(query, UserDocument.class).stream().map(MongoUserRepository::toDomain).toList();
    }

    @Override
    public List<User> findAllById(Collection<String> ids) {
        return mongo.find(Query.query(Criteria.where("id").in(ids)), UserDocument.class).stream()
                .map(MongoUserRepository::toDomain).toList();
    }

    @Override
    public User save(User user) {
        UserDocument doc = user.id() == null ? new UserDocument() : mongo.findById(user.id(), UserDocument.class);
        if (doc == null) {
            doc = new UserDocument();
        }
        doc.id = user.id();
        doc.name = user.name();
        doc.email = user.email();
        doc.password = user.passwordHash();
        doc.photo = user.photo();
        doc.role = user.role().wireValue();
        doc.gender = user.gender() == null ? null : user.gender().wireValue();
        doc.dob = user.dob() == null ? null : Date.from(user.dob().atStartOfDay(ZoneOffset.UTC).toInstant());
        return toDomain(mongo.save(doc));
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), UserDocument.class);
    }

    private static User toDomain(UserDocument d) {
        return new User(d.id, d.name, d.email, d.password, d.photo,
                d.role == null ? Role.USER : Role.fromWire(d.role),
                d.gender == null ? null : Gender.fromWire(d.gender),
                d.dob == null ? null : d.dob.toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                d.createdAt);
    }
}
