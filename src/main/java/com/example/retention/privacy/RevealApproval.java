package com.example.retention.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 临时还原审批台：对已遮蔽实体申请限时去遮蔽（还原），经审批后在有效期内可生成带还原的视图。
 * 申请-审批全程留痕；审批单只授权指定实体/片段、指定角色用途与案件目的，到期或被撤销即失效；
 * 每张带还原的视图必须回链审批单，审批失效会使关联物化视图失效并拦截新下载。
 */
@Entity
@Table(name = "reveal_approval", uniqueConstraints = @UniqueConstraint(columnNames = "approvalNo"))
public class RevealApproval {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    private UUID id;

    /** 人类可读审批单号。 */
    @Column(nullable = false, unique = true)
    private String approvalNo;

    @Column(nullable = false)
    private String requesterRole;

    @Column(nullable = false)
    private String purpose;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> caseScope;

    @Column(nullable = false)
    private UUID recordingId;

    /** 授权还原的敏感实体 id 集合（最小授权，不接受整录音通配）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> entityIds;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private String status;

    private String approver;

    private String decisionNote;

    /** 还原有效期（截止时刻）。 */
    private Instant validUntil;

    private Instant decidedAt;

    @Column(nullable = false)
    private long createdSeq;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getApprovalNo() { return approvalNo; }
    public void setApprovalNo(String approvalNo) { this.approvalNo = approvalNo; }
    public String getRequesterRole() { return requesterRole; }
    public void setRequesterRole(String requesterRole) { this.requesterRole = requesterRole; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public List<String> getCaseScope() { return caseScope; }
    public void setCaseScope(List<String> caseScope) { this.caseScope = caseScope; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public List<String> getEntityIds() { return entityIds; }
    public void setEntityIds(List<String> entityIds) { this.entityIds = entityIds; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public long getCreatedSeq() { return createdSeq; }
    public void setCreatedSeq(long createdSeq) { this.createdSeq = createdSeq; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
