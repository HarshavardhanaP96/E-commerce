package com.sixpack.audit;

/** Public API of the audit module. Recording never throws: auditing must not fail the action it describes. */
public interface AuditTrail {

    void record(AuditEvent event);
}
