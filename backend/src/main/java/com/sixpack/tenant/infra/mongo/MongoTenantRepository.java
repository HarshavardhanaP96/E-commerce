package com.sixpack.tenant.infra.mongo;

import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoTenantRepository implements TenantRepository {

    private final MongoTemplate mongo;

    MongoTenantRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return one(Query.query(Criteria.where("slug").is(slug)));
    }

    @Override
    public Optional<Tenant> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, TenantDocument.class)).map(MongoTenantRepository::toDomain);
    }

    @Override
    public Optional<Tenant> findByDomain(String domain) {
        return one(Query.query(Criteria.where("domains").is(domain)));
    }

    @Override
    public List<Tenant> findAll() {
        return mongo.findAll(TenantDocument.class).stream().map(MongoTenantRepository::toDomain).toList();
    }

    @Override
    public Tenant save(Tenant t) {
        TenantDocument doc = t.id() == null ? null : mongo.findById(t.id(), TenantDocument.class);
        if (doc == null) {
            doc = new TenantDocument();
        }
        doc.id = t.id();
        doc.slug = t.slug();
        doc.name = t.name();
        doc.status = t.status().name();
        doc.domains = t.domains();
        doc.theme = t.theme();
        doc.locale = t.locale();
        doc.currency = t.currency();
        doc.legal = new TenantDocument.LegalDoc();
        doc.legal.termsVersion = t.legal().termsVersion();
        doc.legal.privacyVersion = t.legal().privacyVersion();
        doc.legal.adminAupVersion = t.legal().adminAupVersion();
        doc.payment = new TenantDocument.PaymentDoc();
        doc.payment.provider = t.payment().provider();
        doc.payment.keyId = t.payment().keyId();
        doc.payment.secretRef = t.payment().secretRef();
        doc.mail = new TenantDocument.MailDoc();
        doc.mail.baseUrl = t.mail().baseUrl();
        StorefrontSettings sf = t.storefront();
        doc.storefront = new TenantDocument.StorefrontDoc();
        doc.storefront.logoUrl = sf.logoUrl();
        doc.storefront.legalName = sf.legalName();
        doc.storefront.address = sf.address();
        doc.storefront.email = sf.email();
        doc.storefront.phone = sf.phone();
        doc.storefront.grievanceOfficer = sf.grievanceOfficer();
        doc.storefront.grievanceEmail = sf.grievanceEmail();
        return toDomain(mongo.save(doc));
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), TenantDocument.class);
    }

    private Optional<Tenant> one(Query query) {
        return Optional.ofNullable(mongo.findOne(query, TenantDocument.class)).map(MongoTenantRepository::toDomain);
    }

    private static Tenant toDomain(TenantDocument d) {
        return new Tenant(d.id, d.slug, d.name, d.status == null ? TenantStatus.ACTIVE : TenantStatus.valueOf(d.status),
                d.domains, d.theme, d.locale, d.currency, d.createdAt,
                d.legal == null ? null : new LegalSettings(d.legal.termsVersion, d.legal.privacyVersion,
                        d.legal.adminAupVersion),
                d.payment == null ? null : new PaymentSettings(d.payment.provider, d.payment.keyId, d.payment.secretRef),
                d.mail == null ? null : new MailSettings(d.mail.baseUrl),
                d.storefront == null ? null : new StorefrontSettings(d.storefront.logoUrl, d.storefront.legalName,
                        d.storefront.address, d.storefront.email, d.storefront.phone, d.storefront.grievanceOfficer,
                        d.storefront.grievanceEmail));
    }
}
