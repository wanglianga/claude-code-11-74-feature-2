package com.example.retention.disposal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 处置候选：整对象候选（eligible=true）或被阻断项（eligible=false，附阻断原因）。
 * 候选被撤销时保留原记录与撤销原因，不物理删除。
 */
@Entity
@Table(name = "disposal_candidate")
public class DisposalCandidate {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false)
    private boolean eligible;

    @Column(nullable = false)
    private String status;

    /** 阻断原因（整对象不合格时逐片段列出）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> blockReasons;

    /** 撤销原因（后到冻结、追溯更正、提交前重新核验未通过等）。 */
    private String revokeReason;

    /** 依赖版本：证据水位、规则版本、求值路线。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> dependency;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant decidedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public boolean isEligible() { return eligible; }
    public void setEligible(boolean eligible) { this.eligible = eligible; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<String> getBlockReasons() { return blockReasons; }
    public void setBlockReasons(List<String> blockReasons) { this.blockReasons = blockReasons; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public Map<String, Object> getDependency() { return dependency; }
    public void setDependency(Map<String, Object> dependency) { this.dependency = dependency; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
