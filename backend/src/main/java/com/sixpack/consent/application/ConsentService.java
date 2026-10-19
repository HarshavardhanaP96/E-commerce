package com.sixpack.consent.application;

import com.sixpack.shared.tenant.TenantSettings;
import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import com.sixpack.consent.ConsentEvidence;
import com.sixpack.consent.ConsentLedger;
import com.sixpack.consent.Purpose;
import com.sixpack.consent.domain.ConsentRecord;
import com.sixpack.consent.domain.ConsentRecord.Source;
import com.sixpack.consent.domain.ConsentRepository;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.EvidenceHasher;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ConsentService implements ConsentLedger {

    /**
     * @param latest  the newest record per purpose
     * @param pending required purposes the user still has to accept at the current version
     * @param history every record, newest first
     */
    public record ConsentState(Map<Purpose, ConsentRecord> latest, List<Purpose> pending,
                               List<ConsentRecord> history) {
    }

    private static final int MAX_USER_AGENT = 256;

    private final ConsentRepository records;
    private final LegalProperties legal;
    private final TenantSettings tenant;
    private final EvidenceHasher hasher;
    private final AuditTrail audit;

    ConsentService(ConsentRepository records, LegalProperties legal, TenantSettings tenant, EvidenceHasher hasher,
                   AuditTrail audit) {
        this.records = records;
        this.legal = legal;
        this.tenant = tenant;
        this.hasher = hasher;
        this.audit = audit;
    }

    @Override
    public void recordRegistration(String userId, boolean marketingOptIn, ConsentEvidence evidence) {
        append(userId, Purpose.TERMS, true, Source.REGISTER, evidence);
        append(userId, Purpose.PRIVACY, true, Source.REGISTER, evidence);
        append(userId, Purpose.MARKETING_EMAIL, marketingOptIn, Source.REGISTER, evidence);
    }

    @Override
    public boolean hasAcceptedCurrent(String userId, Purpose purpose) {
        return isCurrentGrant(latest(records.findByUser(userId)).get(purpose));
    }

    @Override
    public String currentVersion(Purpose purpose) {
        // Marketing consent is described in the Privacy Policy, so it follows that version.
        return switch (purpose) {
            case TERMS -> terms();
            case ADMIN_AUP -> adminAup();
            case PRIVACY, MARKETING_EMAIL -> privacy();
        };
    }

    public Map<Purpose, String> currentVersions() {
        return Map.of(Purpose.TERMS, terms(), Purpose.PRIVACY, privacy(), Purpose.ADMIN_AUP, adminAup());
    }

    // A tenant's own version wins; anything it has not set falls back to the platform default (app.legal.*).
    private String terms() {
        return firstNonBlank(tenant.legal().termsVersion(), legal.termsVersion());
    }

    private String privacy() {
        return firstNonBlank(tenant.legal().privacyVersion(), legal.privacyVersion());
    }

    private String adminAup() {
        return firstNonBlank(tenant.legal().adminAupVersion(), legal.adminAupVersion());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** @param admin whether the caller holds the ADMIN role (taken from the JWT; {@code consent} can't call {@code user}) */
    public ConsentState state(String userId, boolean admin) {
        List<ConsentRecord> history = records.findByUser(userId);
        Map<Purpose, ConsentRecord> latest = latest(history);
        List<Purpose> pending = Arrays.stream(Purpose.values())
                .filter(p -> p.requiredFor(admin))
                .filter(p -> !isCurrentGrant(latest.get(p)))
                .toList();
        return new ConsentState(latest, pending, history);
    }

    public ConsentState decide(String userId, boolean admin, Purpose purpose, boolean granted, Source source,
                               ConsentEvidence evidence) {
        if (source == Source.REGISTER) {
            throw ApiException.badRequest("source: must be reprompt or settings");
        }
        if (purpose.adminOnly() && !admin) {
            throw ApiException.badRequest("purpose: only admins can accept this agreement");
        }
        if (purpose.required() && !granted) {
            throw ApiException.badRequest(purpose.adminOnly()
                    ? "The admin agreement can't be withdrawn while you are an admin."
                    : "The Terms and Privacy Policy can't be withdrawn while you have an account. "
                            + "Delete your account instead.");
        }
        append(userId, purpose, granted, source, evidence);
        // The ledger is the legal proof; this entry only puts the decision in the admin audit view.
        audit.record(AuditEvent.of(AuditAction.CONSENT_CHANGED).on("user", userId).changing(List.of(
                FieldChange.of("purpose", null, purpose.wireValue()), FieldChange.of("granted", null, granted),
                FieldChange.of("version", null, currentVersion(purpose)))));
        return state(userId, admin);
    }

    private void append(String userId, Purpose purpose, boolean granted, Source source, ConsentEvidence evidence) {
        String userAgent = evidence.userAgent() == null ? ""
                : evidence.userAgent().substring(0, Math.min(evidence.userAgent().length(), MAX_USER_AGENT));
        records.append(new ConsentRecord(null, userId, purpose, granted, currentVersion(purpose), source,
                hasher.hash(evidence.ip()), userAgent, null));
    }

    private boolean isCurrentGrant(ConsentRecord record) {
        return record != null && record.granted() && record.policyVersion().equals(currentVersion(record.purpose()));
    }

    private static Map<Purpose, ConsentRecord> latest(List<ConsentRecord> newestFirst) {
        Map<Purpose, ConsentRecord> latest = new EnumMap<>(Purpose.class);
        newestFirst.forEach(r -> latest.putIfAbsent(r.purpose(), r));
        return latest;
    }
}
