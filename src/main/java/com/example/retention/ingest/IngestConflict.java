package com.example.retention.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 冲突待核：相同来源版本但内容不同的到达被保留下来等待核查，不静默覆盖。 */
@Entity
@Table(name = "ingest_conflict")
public class IngestConflict {

    @Id
    private UUID id;

    /** RECORDING / EVENT。 */
    @Column(nullable = false)
    private String entityType;

    /** 自然键（来源对象/事件标识）。 */
    @Column(nullable = false)
    private String naturalKey;

    @Column(nullable = false)
    private int sourceVersion;

    @Column(nullable = false)
    private String existingHash;

    @Column(nullable = false)
    private String incomingHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> incomingPayload;

    /** OPEN / RESOLVED。 */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getNaturalKey() { return naturalKey; }
    public void setNaturalKey(String naturalKey) { this.naturalKey = naturalKey; }
    public int getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(int sourceVersion) { this.sourceVersion = sourceVersion; }
    public String getExistingHash() { return existingHash; }
    public void setExistingHash(String existingHash) { this.existingHash = existingHash; }
    public String getIncomingHash() { return incomingHash; }
    public void setIncomingHash(String incomingHash) { this.incomingHash = incomingHash; }
    public Map<String, Object> getIncomingPayload() { return incomingPayload; }
    public void setIncomingPayload(Map<String, Object> incomingPayload) { this.incomingPayload = incomingPayload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
