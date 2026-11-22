package com.example.retention.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * 敏感实体在不可变原转写上的一次出现（字段位置回链）。
 * 位置以 (segmentId, startOffset, endOffset) 表达，offset 为片段转写字符串的字符下标；
 * 跨两段的实体对应同一 entityId 下的多条提及（前缀落在前段、后缀落在后段，matchMode 区分）。
 * 视图遮蔽与临时还原都按提及位置作用于副本，原转写行永不修改。
 */
@Entity
@Table(name = "sensitive_entity_mention",
        uniqueConstraints = @UniqueConstraint(columnNames = {"entityId", "segmentId", "startOffset", "endOffset"}))
public class SensitiveEntityMention {

    /** 完整出现于单片段。 */
    public static final String MODE_FULL = "FULL";
    /** 跨段实体在前片段的部分。 */
    public static final String MODE_PREFIX = "PREFIX";
    /** 跨段实体在后片段的部分。 */
    public static final String MODE_SUFFIX = "SUFFIX";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID entityId;

    @Column(nullable = false)
    private UUID segmentId;

    @Column(nullable = false)
    private int segmentSeq;

    @Column(nullable = false)
    private int startOffset;

    @Column(nullable = false)
    private int endOffset;

    /** 该位置在原转写中覆盖的原文（证据回链的一部分，便于核验遮蔽完整性）。 */
    @Column(nullable = false, columnDefinition = "text")
    private String coveredText;

    /** FULL / PREFIX / SUFFIX。 */
    @Column(nullable = false)
    private String matchMode;

    /** 同一实体下提及的稳定顺序（按片段 seq 与偏移）。 */
    @Column(nullable = false)
    private int mentionSeq;

    @Column(nullable = false)
    private long registeredSeq;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public UUID getSegmentId() { return segmentId; }
    public void setSegmentId(UUID segmentId) { this.segmentId = segmentId; }
    public int getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(int segmentSeq) { this.segmentSeq = segmentSeq; }
    public int getStartOffset() { return startOffset; }
    public void setStartOffset(int startOffset) { this.startOffset = startOffset; }
    public int getEndOffset() { return endOffset; }
    public void setEndOffset(int endOffset) { this.endOffset = endOffset; }
    public String getCoveredText() { return coveredText; }
    public void setCoveredText(String coveredText) { this.coveredText = coveredText; }
    public String getMatchMode() { return matchMode; }
    public void setMatchMode(String matchMode) { this.matchMode = matchMode; }
    public int getMentionSeq() { return mentionSeq; }
    public void setMentionSeq(int mentionSeq) { this.mentionSeq = mentionSeq; }
    public long getRegisteredSeq() { return registeredSeq; }
    public void setRegisteredSeq(long registeredSeq) { this.registeredSeq = registeredSeq; }
}
