package com.example.retention.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 接入账-业务事件：账户、产品终止、催收结案、投诉及案件系统发送的事件，
 * 带业务生效时间与平台接收时间；事件是世界状态的唯一事实来源（可据此复演任意水位）。
 */
@Entity
@Table(name = "business_event", uniqueConstraints = @UniqueConstraint(columnNames = "idempotencyKey"))
public class BusinessEvent {

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_CONFLICT = "CONFLICT_PENDING";

    @Id
    private UUID id;

    /** 全局水位序号：复演时按 seq 截断。 */
    @Column(nullable = false, unique = true)
    private long seq;

    @Column(nullable = false)
    private String sourceSystem;

    @Column(nullable = false)
    private String sourceEventId;

    @Column(nullable = false)
    private int eventVersion;

    @Column(nullable = false)
    private String eventType;

    /** 业务生效时间。 */
    @Column(nullable = false)
    private Instant businessEffectiveTime;

    /** 平台接收时间。 */
    @Column(nullable = false)
    private Instant platformReceivedTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    /** 内容哈希：相同版本相同内容重复到达不重复应用；相同版本内容不同进入冲突待核。 */
    @Column(nullable = false)
    private String payloadHash;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String status;

    /** 来源修订链：同一来源事件的上一版。 */
    private UUID supersedesId;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String sourceEventId) { this.sourceEventId = sourceEventId; }
    public int getEventVersion() { return eventVersion; }
    public void setEventVersion(int eventVersion) { this.eventVersion = eventVersion; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getBusinessEffectiveTime() { return businessEffectiveTime; }
    public void setBusinessEffectiveTime(Instant businessEffectiveTime) { this.businessEffectiveTime = businessEffectiveTime; }
    public Instant getPlatformReceivedTime() { return platformReceivedTime; }
    public void setPlatformReceivedTime(Instant platformReceivedTime) { this.platformReceivedTime = platformReceivedTime; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getSupersedesId() { return supersedesId; }
    public void setSupersedesId(UUID supersedesId) { this.supersedesId = supersedesId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
