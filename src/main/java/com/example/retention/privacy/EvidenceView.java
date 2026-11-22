package com.example.retention.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 证据视图：从不可变原转写按案件目的生成的遮蔽投影，分两条路线：
 * ON_DEMAND（调阅时即时生成，不留长期副本）与 MATERIALIZED（经批准的常用角色视图，预先物化）。
 * 每个视图都固定证据水位、遮蔽规则版本、实体定位修订版本与片段指纹，
 * 遮蔽内容逐处回链字段位置（segment/offset）、所用规则版本与临时还原审批；
 * 物化视图在规则修订、转写更正或案件范围变化时被准确失效（STALE），失效后新下载被拦截。
 */
@Entity
@Table(name = "evidence_view", uniqueConstraints = @UniqueConstraint(columnNames = "viewCode"))
public class EvidenceView {

    public static final String ROUTE_ON_DEMAND = "ON_DEMAND";
    public static final String ROUTE_MATERIALIZED = "MATERIALIZED";

    public static final String STATUS_FRESH = "FRESH";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    private UUID id;

    /** 自然键：路线+录音+角色+用途+案件范围+规则版本的稳定编码，物化去重与查找使用。 */
    @Column(nullable = false, unique = true)
    private String viewCode;

    @Column(nullable = false)
    private String route;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false)
    private String requesterRole;

    @Column(nullable = false)
    private String purpose;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> caseScope;

    /** 生成时固定的证据水位。 */
    @Column(nullable = false)
    private long watermark;

    /** 规则版本绑定：[{ruleId, ruleCode, version, registeredSeq, configHash}]。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> rulePins;

    /** 实体定位修订绑定：[{entityId, entityType, revision, registeredSeq}]。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> entityPins;

    /** 片段指纹：[{segmentId, segmentSeq, contentHash}]，转写更正后用于失效判定。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> segmentFingerprints;

    /** 案件范围签名：范围变化即签名变化。 */
    @Column(nullable = false)
    private String scopeSignature;

    /** 遮蔽结果（含逐处回链）：[{segmentId, segmentSeq, maskedTranscript, masks:[...]}]。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> maskedContent;

    /** 遮蔽结果内容指纹。 */
    @Column(nullable = false)
    private String contentHash;

    /** 临时还原审批（无还原则为空）。 */
    private UUID revealApprovalId;

    /** 失效原因（规则修订/转写更正/案件范围收窄/审批到期等）。 */
    private String invalidateReason;

    private Instant invalidatedAt;

    @Column(nullable = false)
    private long createdSeq;

    @Column(nullable = false)
    private Instant createdAt;

    /** 下载次数（审计）。 */
    @Column(nullable = false)
    private int downloadCount;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getViewCode() { return viewCode; }
    public void setViewCode(String viewCode) { this.viewCode = viewCode; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public String getRequesterRole() { return requesterRole; }
    public void setRequesterRole(String requesterRole) { this.requesterRole = requesterRole; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public List<String> getCaseScope() { return caseScope; }
    public void setCaseScope(List<String> caseScope) { this.caseScope = caseScope; }
    public long getWatermark() { return watermark; }
    public void setWatermark(long watermark) { this.watermark = watermark; }
    public List<Map<String, Object>> getRulePins() { return rulePins; }
    public void setRulePins(List<Map<String, Object>> rulePins) { this.rulePins = rulePins; }
    public List<Map<String, Object>> getEntityPins() { return entityPins; }
    public void setEntityPins(List<Map<String, Object>> entityPins) { this.entityPins = entityPins; }
    public List<Map<String, Object>> getSegmentFingerprints() { return segmentFingerprints; }
    public void setSegmentFingerprints(List<Map<String, Object>> segmentFingerprints) { this.segmentFingerprints = segmentFingerprints; }
    public String getScopeSignature() { return scopeSignature; }
    public void setScopeSignature(String scopeSignature) { this.scopeSignature = scopeSignature; }
    public List<Map<String, Object>> getMaskedContent() { return maskedContent; }
    public void setMaskedContent(List<Map<String, Object>> maskedContent) { this.maskedContent = maskedContent; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public UUID getRevealApprovalId() { return revealApprovalId; }
    public void setRevealApprovalId(UUID revealApprovalId) { this.revealApprovalId = revealApprovalId; }
    public String getInvalidateReason() { return invalidateReason; }
    public void setInvalidateReason(String invalidateReason) { this.invalidateReason = invalidateReason; }
    public Instant getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(Instant invalidatedAt) { this.invalidatedAt = invalidatedAt; }
    public long getCreatedSeq() { return createdSeq; }
    public void setCreatedSeq(long createdSeq) { this.createdSeq = createdSeq; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getDownloadCount() { return downloadCount; }
    public void setDownloadCount(int downloadCount) { this.downloadCount = downloadCount; }
}
