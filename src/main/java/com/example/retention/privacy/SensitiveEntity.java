package com.example.retention.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * 敏感实体（实体定位器输出）：身份证号、银行卡号、住址、健康情况等。
 * 同一逻辑实体经历识别修正时产生新行并通过 supersedesId 连成修正链；
 * 实体本身不直接覆盖转写文字，其在原转写中的每个出现位置由
 * {@link SensitiveEntityMention} 记录，跨片段实体拥有多条提及。
 * 原转写不可变，修正只追加定位结果，从不回写片段文本。
 */
@Entity
@Table(name = "sensitive_entity", uniqueConstraints = @UniqueConstraint(columnNames = "registeredSeq"))
public class SensitiveEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    private UUID id;

    /** 登记水位：定位结果同样纳入全局证据水位，视图可固定截点。 */
    @Column(nullable = false, unique = true)
    private long registeredSeq;

    @Column(nullable = false)
    private UUID recordingId;

    /** 实体类型：ID_CARD / BANK_CARD / ADDRESS / HEALTH。 */
    @Column(nullable = false)
    private String entityType;

    /** 当前识别口径的规范值（修正后值）；仅用于比对与展示，不回写转写。 */
    @Column(nullable = false)
    private String canonicalValue;

    /** 同一逻辑实体的识别修正版本，从 1 起递增。 */
    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private String status;

    /** 修正链：指向被本版本取代的上一版定位。 */
    private UUID supersedesId;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getRegisteredSeq() { return registeredSeq; }
    public void setRegisteredSeq(long registeredSeq) { this.registeredSeq = registeredSeq; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getCanonicalValue() { return canonicalValue; }
    public void setCanonicalValue(String canonicalValue) { this.canonicalValue = canonicalValue; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getSupersedesId() { return supersedesId; }
    public void setSupersedesId(UUID supersedesId) { this.supersedesId = supersedesId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
