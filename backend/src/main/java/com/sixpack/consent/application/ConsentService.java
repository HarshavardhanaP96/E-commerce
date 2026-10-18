package com.sixpack.consent.application;

import com.sixpack.consent.ConsentEvidence;
import com.sixpack.consent.ConsentLedger;
import com.sixpack.consent.Purpose;
import com.sixpack.consent.domain.ConsentRecord;
import com.sixpack.consent.domain.ConsentRecord.Source;
import com.sixpack.consent.domain.ConsentRepository;
import com.sixpack.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
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

    ConsentService(ConsentRepository records, LegalProperties legal) {
        this.records = records;
        this.legal = legal;
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
            case TERMS -> legal.termsVersion();
            case ADMIN_AUP -> legal.adminAupVersion();
            case PRIVACY, MARKETING_EMAIL -> legal.privacyVersion();
        };
    }

    public Map<Purpose, String> currentVersions() {
        return Map.of(Purpose.TERMS, legal.termsVersion(), Purpose.PRIVACY, legal.privacyVersion(),
                Purpose.ADMIN_AUP, legal.adminAupVersion());
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
        return state(userId, admin);
    }

    private void append(String userId, Purpose purpose, boolean granted, Source source, ConsentEvidence evidence) {
        String userAgent = evidence.userAgent() == null ? ""
                : evidence.userAgent().substring(0, Math.min(evidence.userAgent().length(), MAX_USER_AGENT));
        records.append(new ConsentRecord(null, userId, purpose, granted, currentVersion(purpose), source,
                hashIp(evidence.ip()), userAgent, null));
    }

    private boolean isCurrentGrant(ConsentRecord record) {
        return record != null && record.granted() && record.policyVersion().equals(currentVersion(record.purpose()));
    }

    private static Map<Purpose, ConsentRecord> latest(List<ConsentRecord> newestFirst) {
        Map<Purpose, ConsentRecord> latest = new EnumMap<>(Purpose.class);
        newestFirst.forEach(r -> latest.putIfAbsent(r.purpose(), r));
        return latest;
    }

    private String hashIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String salt = legal.ipHashSalt() == null ? "" : legal.ipHashSalt();
            return HexFormat.of().formatHex(sha.digest((salt + ip).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
