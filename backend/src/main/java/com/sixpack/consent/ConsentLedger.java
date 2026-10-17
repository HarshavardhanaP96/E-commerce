package com.sixpack.consent;

/** Public API of the consent module for other modules. */
public interface ConsentLedger {

    /** Records the Terms + Privacy acceptance and the marketing choice made on the registration form. */
    void recordRegistration(String userId, boolean marketingOptIn, ConsentEvidence evidence);

    /** @return whether the user's latest decision for this purpose is a grant of the current policy version */
    boolean hasAcceptedCurrent(String userId, Purpose purpose);

    /** @return the policy version currently in force for this purpose */
    String currentVersion(Purpose purpose);
}
