package com.example.retention.disposal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 事实冲突与升级记录：已执行的处置遭遇后到冻结/追溯更正时生成，
 * 只记录冲突事实并升级处理，不伪称恢复了已删除原件。
 */
@Entity
@Table(name = "escalation_record")
public class EscalationRecord {

    public static final String KIND_EXECUTED_DISPOSAL_CONFLICT = "EXECUTED_DISPOSAL_FACT_CONFLICT";
    public static final String KIND_RECEIPT_CONFLICT = "RECEIPT_CONFLICT";
    public static final String KIND_DISPOSAL_AFTER_REVOKE = "DISPOSAL_AFTER_REVOKE";

    @Id
    private UUID id;

    @Column(nullable = false)
    private String kind;

    private UUID recordingId;

    private UUID candidateId;

    private String operationNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> details;

    /** OPEN / ACKED。 */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public UUID getCandidateId() { return candidateId; }
    public void setCandidateId(UUID candidateId) { this.candidateId = candidateId; }
    public String getOperationNo() { return operationNo; }
    public void setOperationNo(String operationNo) { this.operationNo = operationNo; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
