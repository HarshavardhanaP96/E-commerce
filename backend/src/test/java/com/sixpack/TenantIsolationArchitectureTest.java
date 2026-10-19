package com.sixpack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Tenant-owned data must only be reached through {@code TenantMongo}. This starts with today's repositories
 * allowlisted, and shrinks: migrating a repository to {@code TenantMongo} means deleting its line below. The
 * test also fails for a listed class that no longer uses {@code MongoTemplate}, so the list cannot go stale.
 */
class TenantIsolationArchitectureTest {

    /** Repositories still on raw MongoTemplate. Remove each as it moves to TenantMongo. */
    private static final Set<String> NOT_YET_MIGRATED = Set.of(
            "com.sixpack.consent.infra.mongo.MongoConsentRepository",
            "com.sixpack.user.infra.mongo.MongoUserRepository",
            "com.sixpack.user.infra.mongo.MongoPasswordResetTokenRepository",
            "com.sixpack.dashboard.infra.mongo.MongoDashboardReadAdapter",
            "com.sixpack.audit.infra.mongo.MongoAuditRepository");

    private static Set<String> classesUsingMongoTemplate() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sixpack").stream()
                .filter(c -> !c.getPackageName().startsWith("com.sixpack.shared.tenant"))
                .filter(c -> !c.getPackageName().startsWith("com.sixpack.tenant"))
                .filter(TenantIsolationArchitectureTest::usesMongoTemplate)
                .map(c -> c.getName().split("\\$")[0])
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean usesMongoTemplate(JavaClass c) {
        return c.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> d.getTargetClass().isAssignableTo(MongoTemplate.class));
    }

    @Test
    void noNewCodeTouchesMongoTemplateDirectly() {
        Set<String> offenders = new TreeSet<>(classesUsingMongoTemplate());
        offenders.removeAll(NOT_YET_MIGRATED);
        assertThat(offenders)
                .as("Use TenantMongo instead of MongoTemplate for tenant-owned data. Global data belongs in the "
                        + "tenant module.")
                .isEmpty();
    }

    @Test
    void theAllowlistOnlyContainsClassesThatStillNeedIt() {
        Set<String> stale = new TreeSet<>(NOT_YET_MIGRATED);
        stale.removeAll(classesUsingMongoTemplate());
        assertThat(stale)
                .as("These no longer use MongoTemplate: delete them from NOT_YET_MIGRATED")
                .isEmpty();
    }
}
