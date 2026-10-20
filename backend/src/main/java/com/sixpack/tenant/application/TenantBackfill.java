package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Gives data that predates multi-tenancy to the default tenant. Only touches documents with no
 * {@code tenantId}, so it is safe to run on every start.
 */
@Component
@Order(2)
public class TenantBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBackfill.class);

    private final MongoTemplate mongo;
    private final TenantRepository tenants;
    private final TenantProperties props;
    private final List<BackfillTarget> targets;

    TenantBackfill(MongoTemplate mongo, TenantRepository tenants, TenantProperties props,
                   List<BackfillTarget> targets) {
        this.mongo = mongo;
        this.tenants = tenants;
        this.props = props;
        this.targets = targets;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /** @return how many documents were assigned */
    public long backfill() {
        if (targets.isEmpty()) {
            return 0;
        }
        Tenant owner = tenants.findBySlug(props.defaultSlug())
                .orElseThrow(() -> new IllegalStateException("Default tenant is missing"));
        long assigned = 0;
        for (BackfillTarget target : targets) {
            long modified = mongo.updateMulti(Query.query(Criteria.where("tenantId").exists(false)),
                    Update.update("tenantId", owner.id()), target.collection()).getModifiedCount();
            if (modified > 0) {
                log.info("Assigned {} document(s) in '{}' to tenant '{}'", modified, target.collection(), owner.slug());
            }
            assigned += modified;
        }
        return assigned;
    }
}
