package com.example.retention.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** 已分类字段：片段上登记的可调阅字段及其密级分类。 */
@Entity
@Table(name = "field_classification")
public class FieldClassification {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID segmentId;

    /** 字段名，如 AUDIO / TRANSCRIPT / CUSTOMER_NAME / ID_NUMBER / BANK_CARD。 */
    @Column(nullable = false)
    private String fieldKey;

    /** 分类：PUBLIC / GENERAL / PII_BASIC / PII_SENSITIVE / FINANCIAL_SENSITIVE。 */
    @Column(nullable = false)
    private String classification;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSegmentId() { return segmentId; }
    public void setSegmentId(UUID segmentId) { this.segmentId = segmentId; }
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
}
