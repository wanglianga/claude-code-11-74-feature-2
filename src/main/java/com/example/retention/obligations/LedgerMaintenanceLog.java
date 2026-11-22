package com.example.retention.obligations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 义务账维护日志：每次更正触发的补偿写入都留痕，用于与路线A的遍历范围对比代价。 */
@Entity
@Table(name = "ledger_maintenance_log")
public class LedgerMaintenanceLog {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String reason;

    /** 触发来源（事件号/规则号）。 */
    private String triggerRef;

    @Column(nullable = false)
    private int segmentsScanned;

    @Column(nullable = false)
    private int entriesAdded;

    @Column(nullable = false)
    private int entriesVoided;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTriggerRef() { return triggerRef; }
    public void setTriggerRef(String triggerRef) { this.triggerRef = triggerRef; }
    public int getSegmentsScanned() { return segmentsScanned; }
    public void setSegmentsScanned(int segmentsScanned) { this.segmentsScanned = segmentsScanned; }
    public int getEntriesAdded() { return entriesAdded; }
    public void setEntriesAdded(int entriesAdded) { this.entriesAdded = entriesAdded; }
    public int getEntriesVoided() { return entriesVoided; }
    public void setEntriesVoided(int entriesVoided) { this.entriesVoided = entriesVoided; }
    public Map<String, Object> getDetail() { return detail; }
    public void setDetail(Map<String, Object> detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
