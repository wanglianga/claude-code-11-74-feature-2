package com.example.retention.graph;

import java.time.LocalDate;
import java.util.Map;

/** 业务事实视图：由事件折叠得到，带最近变更水位号。 */
public record FactView(
        String factKey,
        String factType,
        String status,        // CONFIRMED / UNCONFIRMED / REVOKED
        LocalDate effectiveDate,
        String caseId,
        String accountId,
        Map<String, Object> attributes,
        long lastChangeSeq,
        int revision) {

    public boolean confirmed() {
        return "CONFIRMED".equals(status);
    }

    public boolean revoked() {
        return "REVOKED".equals(status);
    }
}
