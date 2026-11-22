package com.example.retention.disposal;

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
 * 执行账：逐对象记录操作号、请求、确认、拒绝与结果未知。
 * 超时计为 UNKNOWN 而非成功；重复回执不重复处置；结果未知须用原操作号核对。
 */
@Entity
@Table(name = "execution_ledger", uniqueConstraints = @UniqueConstraint(columnNames = "operationNo"))
public class ExecutionLedger {

    public static final String RECEIPT_NONE = "NONE";
    public static final String RECEIPT_CONFIRMED = "CONFIRMED";
    public static final String RECEIPT_REJECTED = "REJECTED";
    public static final String RECEIPT_UNKNOWN = "UNKNOWN";

    public static final String RESULT_PENDING = "PENDING";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_UNKNOWN = "UNKNOWN";

    @Id
    private UUID id;

    /** 操作号：一次处置提交的唯一编号，对账与回执都凭它。 */
    @Column(nullable = false)
    private String operationNo;

    @Column(nullable = false)
    private UUID candidateId;

    @Column(nullable = false)
    private UUID recordingId;

    /** 提交给（模拟）存储平台的请求。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> requestPayload;

    @Column(nullable = false)
    private Instant submittedAt;

    /** NONE / CONFIRMED / REJECTED / UNKNOWN。 */
    @Column(nullable = false)
    private String receiptStatus;

    /** PENDING / SUCCESS / FAILED / UNKNOWN。 */
    @Column(nullable = false)
    private String finalResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> receiptPayload;

    private Instant receiptReceivedAt;

    /** 重复回执计数：不重复处置，只记录。 */
    @Column(nullable = false)
    private int duplicateReceipts;

    private Instant lastReconciledAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getOperationNo() { return operationNo; }
    public void setOperationNo(String operationNo) { this.operationNo = operationNo; }
    public UUID getCandidateId() { return candidateId; }
    public void setCandidateId(UUID candidateId) { this.candidateId = candidateId; }
    public UUID getRecordingId() { return recordingId; }
    public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
    public Map<String, Object> getRequestPayload() { return requestPayload; }
    public void setRequestPayload(Map<String, Object> requestPayload) { this.requestPayload = requestPayload; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public String getReceiptStatus() { return receiptStatus; }
    public void setReceiptStatus(String receiptStatus) { this.receiptStatus = receiptStatus; }
    public String getFinalResult() { return finalResult; }
    public void setFinalResult(String finalResult) { this.finalResult = finalResult; }
    public Map<String, Object> getReceiptPayload() { return receiptPayload; }
    public void setReceiptPayload(Map<String, Object> receiptPayload) { this.receiptPayload = receiptPayload; }
    public Instant getReceiptReceivedAt() { return receiptReceivedAt; }
    public void setReceiptReceivedAt(Instant receiptReceivedAt) { this.receiptReceivedAt = receiptReceivedAt; }
    public int getDuplicateReceipts() { return duplicateReceipts; }
    public void setDuplicateReceipts(int duplicateReceipts) { this.duplicateReceipts = duplicateReceipts; }
    public Instant getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(Instant lastReconciledAt) { this.lastReconciledAt = lastReconciledAt; }
}
