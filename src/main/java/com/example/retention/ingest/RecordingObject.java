package com.example.retention.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * 接入账-录音对象：保存对象标识、哈希、声道、时长、两类时间、来源修订链与幂等键。
 * 同一来源对象的新版本产生新行并通过 supersedesId 连成修订链。
 */
@Entity
@Table(name = "recording_object", uniqueConstraints = @UniqueConstraint(columnNames = "idempotencyKey"))
public class RecordingObject {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_DISPOSED = "DISPOSED";

    @Id
    private UUID id;

    /** 全局水位序号（接入也占用水位，保证快照可固定接入截点）。 */
    @Column(nullable = false, unique = true)
    private long seq;

    @Column(nullable = false)
    private String sourceSystem;

    @Column(nullable = false)
    private String sourceObjectId;

    @Column(nullable = false)
    private int sourceVersion;

    /** 录音内容哈希（模拟对象的内容指纹）。 */
    @Column(nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private int channelCount;

    @Column(nullable = false)
    private long durationMs;

    /** 业务时间（通话发生时间）。 */
    @Column(nullable = false)
    private Instant callBusinessTime;

    /** 平台接收时间。 */
    @Column(nullable = false)
    private Instant platformReceivedTime;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String status;

    /** 来源修订链：指向被本版本取代的上一版对象。 */
    private UUID supersedesId;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceObjectId() { return sourceObjectId; }
    public void setSourceObjectId(String sourceObjectId) { this.sourceObjectId = sourceObjectId; }
    public int getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(int sourceVersion) { this.sourceVersion = sourceVersion; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public int getChannelCount() { return channelCount; }
    public void setChannelCount(int channelCount) { this.channelCount = channelCount; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Instant getCallBusinessTime() { return callBusinessTime; }
    public void setCallBusinessTime(Instant callBusinessTime) { this.callBusinessTime = callBusinessTime; }
    public Instant getPlatformReceivedTime() { return platformReceivedTime; }
    public void setPlatformReceivedTime(Instant platformReceivedTime) { this.platformReceivedTime = platformReceivedTime; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getSupersedesId() { return supersedesId; }
    public void setSupersedesId(UUID supersedesId) { this.supersedesId = supersedesId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
