package com.example.retention.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 视图下载留痕：每次对证据视图的下载尝试都追加一条不可变记录（放行或拦截）。
 * 失效视图、过期审批、案件范围收窄后的新下载被拦截时以 GRANTED=false 记录原因，
 * 用于核验“新下载拦截”与审计追溯。
 */
@Entity
@Table(name = "evidence_view_download")
public class EvidenceViewDownload {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID viewId;

    @Column(nullable = false)
    private String route;

    @Column(nullable = false)
    private boolean granted;

    @Column(nullable = false)
    private String requesterRole;

    @Column(nullable = false)
    private String purpose;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(nullable = false)
    private long seq;

    @Column(nullable = false)
    private Instant at;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getViewId() { return viewId; }
    public void setViewId(UUID viewId) { this.viewId = viewId; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public boolean isGranted() { return granted; }
    public void setGranted(boolean granted) { this.granted = granted; }
    public String getRequesterRole() { return requesterRole; }
    public void setRequesterRole(String requesterRole) { this.requesterRole = requesterRole; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
