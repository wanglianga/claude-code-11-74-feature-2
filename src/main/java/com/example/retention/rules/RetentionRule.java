package com.example.retention.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 规则目录：登记适用事实、起算事件、保留期限、适用区间、用途与字段集合。
 * 同一 ruleCode 的多版本构成规则修订史；retroactive=true 的版本对适用区间内既有事实追溯生效。
 */
@Entity
@Table(name = "retention_rule", uniqueConstraints = @UniqueConstraint(columnNames = {"ruleCode", "version"}))
public class RetentionRule {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    private UUID id;

    /** 登记水位：规则可见性也按水位截断。 */
    @Column(nullable = false, unique = true)
    private long registeredSeq;

    @Column(nullable = false)
    private String ruleCode;

    @Column(nullable = false)
    private int version;

    /** 适用事实类型，如 PRODUCT_TERMINATION / COLLECTION_CLOSURE。 */
    @Column(nullable = false)
    private String factType;

    /** 起算事件类型，如 PRODUCT_TERMINATED / COLLECTION_CLOSED。 */
    @Column(nullable = false)
    private String triggerEventType;

    /** 保留期限（年）。 */
    @Column(nullable = false)
    private int retentionYears;

    /** 适用区间：按事实的业务生效日期判断。 */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    /** 用途，如 SALES_REVIEW / COLLECTION / AUDIT。 */
    @Column(nullable = false)
    private String purpose;

    /** 字段集合：该用途下调阅可投影的字段。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> fieldSet;

    /** 是否对适用区间内既有事实追溯生效。 */
    @Column(nullable = false)
    private boolean retroactive;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean covers(LocalDate factEffectiveDate) {
        if (factEffectiveDate == null) return false;
        if (factEffectiveDate.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || !factEffectiveDate.isAfter(effectiveTo);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getRegisteredSeq() { return registeredSeq; }
    public void setRegisteredSeq(long registeredSeq) { this.registeredSeq = registeredSeq; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getFactType() { return factType; }
    public void setFactType(String factType) { this.factType = factType; }
    public String getTriggerEventType() { return triggerEventType; }
    public void setTriggerEventType(String triggerEventType) { this.triggerEventType = triggerEventType; }
    public int getRetentionYears() { return retentionYears; }
    public void setRetentionYears(int retentionYears) { this.retentionYears = retentionYears; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public List<String> getFieldSet() { return fieldSet; }
    public void setFieldSet(List<String> fieldSet) { this.fieldSet = fieldSet; }
    public boolean isRetroactive() { return retroactive; }
    public void setRetroactive(boolean retroactive) { this.retroactive = retroactive; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
