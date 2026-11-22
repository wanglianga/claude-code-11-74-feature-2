package com.example.retention.obligations;

import java.time.LocalDate;
import java.util.UUID;

/** 一条有效义务及其来源：规则版本、事实、起算日期、到期日、依据与状态。 */
public record ObligationView(
        String ruleCode,
        Integer ruleVersion,
        UUID ruleId,
        String factKey,
        String factType,
        String factStatus,
        LocalDate triggerDate,
        LocalDate expiryDate,
        String basis,   // RULE_CONFIRMED / BASIS_UNDETERMINED
        String state    // RETAINED / EXPIRED / PENDING
) {

    /** 同一义务的身份（用于路线A派生与路线B账载对齐、补偿差分）。 */
    public String identity() {
        return ruleCode + "#" + ruleVersion + "|" + factKey + "|" + triggerDate + "|" + expiryDate + "|" + basis;
    }
}
