package com.example.retention.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** 接入账-片段：转写平台按时间片段返回的文字与片段边界。 */
@Entity
@Table(name = "recording_segment")
public class RecordingSegment {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false)
    private int seq;

    /** 片段边界（毫秒）。 */
    @Column(nullable = false)
    private long startMs;

    @Column(nullable = false)
    private long endMs;

    @Column(columnDefinition = "text")
    private String transcript;

    @Column(nullable = false)
    private String contentHash;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public long getStartMs() { return startMs; }
    public void setStartMs(long startMs) { this.startMs = startMs; }
    public long getEndMs() { return endMs; }
    public void setEndMs(long endMs) { this.endMs = endMs; }
    public String getTranscript() { return transcript; }
    public void setTranscript(String transcript) { this.transcript = transcript; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
