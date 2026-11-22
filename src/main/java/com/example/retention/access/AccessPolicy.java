package com.example.retention.access;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 虚构调阅策略：角色 → 可及分类与可用用途。
 * 保留义务不等于任意角色可查看全部内容；限制展示也不等于可以删除。
 */
@Component
public class AccessPolicy {

    private static final Set<String> ALL_CLASSES = Set.of(
            "PUBLIC", "GENERAL", "PII_BASIC", "PII_SENSITIVE", "FINANCIAL_SENSITIVE");

    private record Grant(Set<String> classifications, Set<String> purposes) {
    }

    private static final Map<String, Grant> GRANTS = Map.of(
            "COMPLIANCE_OFFICER", new Grant(ALL_CLASSES,
                    Set.of("SALES_REVIEW", "COLLECTION", "AUDIT", "COMPLIANCE", "CUSTOMER_CARE")),
            "AUDITOR", new Grant(ALL_CLASSES, Set.of("AUDIT", "COMPLIANCE")),
            "COLLECTION_AGENT", new Grant(Set.of("PUBLIC", "GENERAL", "PII_BASIC"),
                    Set.of("COLLECTION")),
            "SALES_SUPERVISOR", new Grant(Set.of("PUBLIC", "GENERAL", "PII_BASIC"),
                    Set.of("SALES_REVIEW")),
            "CUSTOMER_SERVICE", new Grant(Set.of("PUBLIC", "GENERAL"),
                    Set.of("CUSTOMER_CARE")));

    public boolean knownRole(String role) {
        return GRANTS.containsKey(role);
    }

    public boolean allowsPurpose(String role, String purpose) {
        Grant g = GRANTS.get(role);
        return g != null && g.purposes().contains(purpose);
    }

    public boolean allowsClassification(String role, String classification) {
        Grant g = GRANTS.get(role);
        return g != null && g.classifications().contains(classification);
    }
}
