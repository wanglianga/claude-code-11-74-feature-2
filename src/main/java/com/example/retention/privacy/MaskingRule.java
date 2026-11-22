package com.example.retention.privacy;

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
 * 遮蔽规则版本库：按 ruleCode + version 不可变版本化，同一 ruleCode 的多版本构成修订史。
 * 规则内容为“实体类型 → 遮蔽动作”的映射（全遮蔽/保留末四位/部分遮蔽及占位符），
 * 视图生成时固定具体版本并回链；规则一旦被视图引用即不可删除，只允许登记新版本取代。
 */
@Entity
@Table(name = "masking_rule",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ruleCode", "version"}),
                @UniqueConstraint(columnNames = "registeredSeq")})
public class MaskingRule {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private long registeredSeq;

    @Column(nullable = false)
    private String ruleCode;

    @Column(nullable = false)
    private int version;

    /** 规则内容：{实体类型: {action, placeholder, keep?}}，动作见 MaskingActions。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> ruleConfig;

    /** 配置内容指纹：视图绑定与失效判定使用。 */
    @Column(nullable = false)
    private String configHash;

    @Column(nullable = false)
    private String changeNote;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getRegisteredSeq() { return registeredSeq; }
    public void setRegisteredSeq(long registeredSeq) { this.registeredSeq = registeredSeq; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Map<String, Object> getRuleConfig() { return ruleConfig; }
    public void setRuleConfig(Map<String, Object> ruleConfig) { this.ruleConfig = ruleConfig; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }
    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
