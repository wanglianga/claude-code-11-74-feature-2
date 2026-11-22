package com.example.retention.obligations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 路线B 的不可变义务账：条目只增不改。
 * 更正通过补偿记录完成——VOID 条目引用被作废的原条目（invalidatesId），
 * RECTIFY 条目登记更正后的新义务；查询某水位下的有效义务 = 未被同水位 VOID 引用的 ORIGINAL/RECTIFY。
 */
@Entity
@Table(name = "obligation_entry")
public class ObligationEntry {

    public static final String KIND_ORIGINAL = "ORIGINAL";
    public static final String KIND_RECTIFY = "RECTIFY";
    public static final String KIND_VOID = "VOID";

    public static final String BASIS_RULE_CONFIRMED = "RULE_CONFIRMED";
    public static final String BASIS_UNDETERMINED = "BASIS_UNDETERMINED";

    @Id
    private UUID id;

    /** 全局水位序号。 */
    @Column(nullable = false, unique = true)
    private long entrySeq;

    @Column(nullable = false)
    private UUID segmentId;

    @Column(nullable = false)
    private UUID recordingId;

    private UUID ruleId;

    private String ruleCode;

    private Integer ruleVersion;

    private String factKey;

    private String factType;

    /** 起算日期（事实业务生效日）。 */
    private LocalDate triggerDate;

    /** 普通保留到期日；未确定依据或 VOID 条目为 null。 */
    private LocalDate expiryDate;

    /** RULE_CONFIRMED / BASIS_UNDETERMINED / NONE(VOID)。 */
    @Column(nullable = false)
    private String basis;

    /** ORIGINAL / RECTIFY / VOID。 */
    @Column(nullable = false)
    private String entryKind;

    /** VOID 条目引用其作废的原条目。 */
    private UUID invalidatesId;

    /** 产生该条目的原因（初始建立 / 某次更正的补偿）。 */
    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getEntrySeq() { return entrySeq; }
    public void setEntrySeq(long entrySeq) { this.entrySeq = entrySeq; }
    public UUID getSegmentId() { return segmentId; }
    public void setSegmentId(UUID segmentId) { this.segmentId = segmentId; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public UUID getRuleId() { return ruleId; }
    public void setRuleId(UUID ruleId) { this.ruleId = ruleId; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getFactKey() { return factKey; }
    public void setFactKey(String factKey) { this.factKey = factKey; }
    public String getFactType() { return factType; }
    public void setFactType(String factType) { this.factType = factType; }
    public LocalDate getTriggerDate() { return triggerDate; }
    public void setTriggerDate(LocalDate triggerDate) { this.triggerDate = triggerDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
    public String getEntryKind() { return entryKind; }
    public void setEntryKind(String entryKind) { this.entryKind = entryKind; }
    public UUID getInvalidatesId() { return invalidatesId; }
    public void setInvalidatesId(UUID invalidatesId) { this.invalidatesId = invalidatesId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
