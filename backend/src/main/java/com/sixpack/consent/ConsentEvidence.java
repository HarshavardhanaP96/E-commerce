package com.sixpack.consent;

/** Request details kept as proof that a consent was given (DPDP: the burden of proof is on us). */
public record ConsentEvidence(String ip, String userAgent) {
}
