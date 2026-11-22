package com.example.retention.privacy;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.ingest.RecordingObjectRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时还原审批台：受理对已遮蔽实体的限时还原申请，经审批后在有效期与最小实体集合内放行去遮蔽。
 * 审批单不可变地记录申请要素与决定；到期/撤销即失效，并使引用它的 FRESH 物化视图同步失效。
 */
@Service
public class RevealApprovalService {

    private final RevealApprovalRepository approvalRepository;
    private final EvidenceViewRepository viewRepository;
    private final SensitiveEntityRepository entityRepository;
    private final RecordingObjectRepository recordingRepository;
    private final WatermarkService watermark;
    private final ScopeValidator scopeValidator;

    public RevealApprovalService(RevealApprovalRepository approvalRepository,
                                 EvidenceViewRepository viewRepository,
                                 SensitiveEntityRepository entityRepository,
                                 RecordingObjectRepository recordingRepository,
                                 WatermarkService watermark,
                                 ScopeValidator scopeValidator) {
        this.approvalRepository = approvalRepository;
        this.viewRepository = viewRepository;
        this.entityRepository = entityRepository;
        this.recordingRepository = recordingRepository;
        this.watermark = watermark;
        this.scopeValidator = scopeValidator;
    }

    public record ApplyInput(String requesterRole, String purpose, List<String> caseScope,
                             UUID recordingId, List<UUID> entityIds, String reason, Long ttlMinutes) {
    }

    @Transactional
    public RevealApproval apply(ApplyInput in) {
        scopeValidator.requireKnownRole(in.requesterRole());
        if (in.caseScope() == null) {
            throw ApiException.badRequest("案件范围 caseScope 不能为空（可为空数组）");
        }
        recordingRepository.findById(in.recordingId())
                .orElseThrow(() -> ApiException.notFound("录音不存在: " + in.recordingId()));
        if (in.entityIds() == null || in.entityIds().isEmpty()) {
            throw ApiException.badRequest("还原申请必须指定最小实体集合，不接受整录音通配");
        }
        if (in.ttlMinutes() == null || in.ttlMinutes() <= 0) {
            throw ApiException.badRequest("还原有效期 ttlMinutes 必须为正数");
        }
        Set<UUID> distinct = new LinkedHashSet<>(in.entityIds());
        for (UUID entityId : distinct) {
            SensitiveEntity e = entityRepository.findById(entityId)
                    .orElseThrow(() -> ApiException.notFound("敏感实体不存在: " + entityId));
            if (!e.getRecordingId().equals(in.recordingId())) {
                throw ApiException.badRequest("实体 " + entityId + " 不属于该录音");
            }
            if (!SensitiveEntity.STATUS_ACTIVE.equals(e.getStatus())) {
                throw ApiException.badRequest("实体 " + entityId + " 已被识别修正取代，不能按旧版还原");
            }
        }
        RevealApproval a = new RevealApproval();
        a.setId(UUID.randomUUID());
        a.setApprovalNo("RA-" + watermark.next());
        a.setRequesterRole(in.requesterRole());
        a.setPurpose(in.purpose());
        a.setCaseScope(in.caseScope());
        a.setRecordingId(in.recordingId());
        a.setEntityIds(distinct.stream().map(UUID::toString).toList());
        a.setReason(in.reason() == null ? "" : in.reason());
        a.setStatus(RevealApproval.STATUS_PENDING);
        a.setValidUntil(Instant.now().plus(in.ttlMinutes(), ChronoUnit.MINUTES));
        a.setCreatedSeq(watermark.next());
        a.setCreatedAt(Instant.now());
        return approvalRepository.save(a);
    }

    @Transactional
    public RevealApproval decide(String approvalNo, boolean approve, String approver, String note) {
        RevealApproval a = approvalRepository.findByApprovalNo(approvalNo)
                .orElseThrow(() -> ApiException.notFound("审批单不存在: " + approvalNo));
        if (!RevealApproval.STATUS_PENDING.equals(a.getStatus())) {
            throw ApiException.conflict("审批单已决定，当前状态: " + a.getStatus());
        }
        a.setStatus(approve ? RevealApproval.STATUS_APPROVED : RevealApproval.STATUS_REJECTED);
        a.setApprover(approver == null ? "" : approver);
        a.setDecisionNote(note == null ? "" : note);
        a.setDecidedAt(Instant.now());
        return approvalRepository.save(a);
    }

    /** 撤销已批准审批，并使引用它的 FRESH 视图失效。 */
    @Transactional
    public RevealApproval revoke(String approvalNo, String note) {
        RevealApproval a = approvalRepository.findByApprovalNo(approvalNo)
                .orElseThrow(() -> ApiException.notFound("审批单不存在: " + approvalNo));
        if (!RevealApproval.STATUS_APPROVED.equals(a.getStatus())) {
            throw ApiException.conflict("仅 APPROVED 审批可撤销，当前状态: " + a.getStatus());
        }
        a.setStatus(RevealApproval.STATUS_REVOKED);
        a.setDecisionNote(note == null ? "" : note);
        a.setDecidedAt(Instant.now());
        approvalRepository.save(a);
        int affected = 0;
        for (EvidenceView v : viewRepository.findByRevealApprovalId(a.getId())) {
            if (EvidenceView.STATUS_FRESH.equals(v.getStatus())) {
                v.setStatus(EvidenceView.STATUS_STALE);
                v.setInvalidateReason("APPROVAL_REVOKED: 临时还原审批 " + approvalNo + " 已撤销");
                v.setInvalidatedAt(Instant.now());
                viewRepository.save(v);
                affected++;
            }
        }
        return a;
    }

    /**
     * 核验审批在当前请求下仍可用于还原：状态、有效期、角色用途、案件范围、录音、实体集合。
     * 到期惰性落账为 EXPIRED 并使关联 FRESH 视图失效。
     */
    @Transactional
    public RevealApproval requireValid(UUID approvalId, String role, String purpose,
                                       List<String> caseScope, UUID recordingId,
                                       Set<UUID> requestedEntityIds) {
        RevealApproval a = approvalRepository.findById(approvalId)
                .orElseThrow(() -> ApiException.notFound("还原审批不存在: " + approvalId));
        if (RevealApproval.STATUS_APPROVED.equals(a.getStatus())
                && Instant.now().isAfter(a.getValidUntil())) {
            a.setStatus(RevealApproval.STATUS_EXPIRED);
            approvalRepository.save(a);
            for (EvidenceView v : viewRepository.findByRevealApprovalId(a.getId())) {
                if (EvidenceView.STATUS_FRESH.equals(v.getStatus())) {
                    v.setStatus(EvidenceView.STATUS_STALE);
                    v.setInvalidateReason("APPROVAL_EXPIRED: 临时还原审批 " + a.getApprovalNo() + " 已过期");
                    v.setInvalidatedAt(Instant.now());
                    viewRepository.save(v);
                }
            }
        }
        if (!RevealApproval.STATUS_APPROVED.equals(a.getStatus())) {
            throw ApiException.forbidden("还原审批不可用，状态: " + a.getStatus());
        }
        if (!a.getRequesterRole().equals(role) || !a.getPurpose().equals(purpose)
                || !a.getRecordingId().equals(recordingId)) {
            throw ApiException.forbidden("还原审批的角色/用途/录音与本次请求不一致");
        }
        if (!new LinkedHashSet<>(a.getCaseScope()).equals(new LinkedHashSet<>(caseScope))) {
            throw ApiException.forbidden("还原审批的案件范围与本次请求不一致");
        }
        Set<UUID> allowed = new LinkedHashSet<>(a.getEntityIds().stream().map(UUID::fromString).toList());
        if (!allowed.containsAll(requestedEntityIds)) {
            throw ApiException.forbidden("请求还原的实体超出审批授权的最小集合");
        }
        return a;
    }

    @Transactional(readOnly = true)
    public RevealApproval findById(UUID id) {
        return approvalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("还原审批不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<RevealApproval> list() {
        return approvalRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<RevealApproval> listForRecording(UUID recordingId) {
        return approvalRepository.findByRecordingIdOrderByCreatedSeq(recordingId);
    }
}
