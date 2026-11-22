package com.example.retention.disposal;

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

/** 处置计划：保存求值截点、路线与依赖的规则版本。 */
@Entity
@Table(name = "disposal_plan")
public class DisposalPlan {

    public static final String STATUS_OPEN = "OPEN";

    @Id
    private UUID id;

    @Column(nullable = false)
    private long createdSeq;

    @Column(nullable = false)
    private LocalDate asOfDate;

    @Column(nullable = false)
    private long watermark;

    /** A=截点遍历派生；B=义务账。 */
    @Column(nullable = false)
    private String route;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> rulePins;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getCreatedSeq() { return createdSeq; }
    public void setCreatedSeq(long createdSeq) { this.createdSeq = createdSeq; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public long getWatermark() { return watermark; }
    public void setWatermark(long watermark) { this.watermark = watermark; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public List<Map<String, Object>> getRulePins() { return rulePins; }
    public void setRulePins(List<Map<String, Object>> rulePins) { this.rulePins = rulePins; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
