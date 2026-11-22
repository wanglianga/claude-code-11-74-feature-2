package com.example.retention.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 调阅快照：固定本次调阅使用的证据水位、关系（由水位隐含）与规则版本，
 * 历史调阅按原快照复演当时判断；新授权或事实修订不替换旧回执。
 */
@Entity
@Table(name = "access_snapshot")
public class AccessSnapshot {

    @Id
    private UUID id;

    @Column(nullable = false)
    private long createdSeq;

    @Column(nullable = false)
    private String requesterRole;

    @Column(nullable = false)
    private String purpose;

    /** 案件范围。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> caseScope;

    @Column(nullable = false)
    private LocalDate asOfDate;

    /** 证据水位：事实、关系、保全的截点。 */
    @Column(nullable = false)
    private long watermark;

    /** 固定的规则版本：[{ruleId, ruleCode, version}]。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> rulePins;

    /** 求值结果：允许片段、字段及拒绝理由。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> result;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getCreatedSeq() { return createdSeq; }
    public void setCreatedSeq(long createdSeq) { this.createdSeq = createdSeq; }
    public String getRequesterRole() { return requesterRole; }
    public void setRequesterRole(String requesterRole) { this.requesterRole = requesterRole; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public List<String> getCaseScope() { return caseScope; }
    public void setCaseScope(List<String> caseScope) { this.caseScope = caseScope; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public long getWatermark() { return watermark; }
    public void setWatermark(long watermark) { this.watermark = watermark; }
    public List<Map<String, Object>> getRulePins() { return rulePins; }
    public void setRulePins(List<Map<String, Object>> rulePins) { this.rulePins = rulePins; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
