package com.example.retention.disposal;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.graph.ChangeImpact;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingObject;
import com.example.retention.ingest.RecordingObjectRepository;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import com.example.retention.obligations.EvalStats;
import com.example.retention.obligations.ObligationCore;
import com.example.retention.obligations.ObligationLedgerService;
import com.example.retention.obligations.RecordingEvaluation;
import com.example.retention.rules.RetentionRule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置编排与模拟执行：候选清单保存依赖版本与阻断原因；提交处置前重新核验义务与保全；
 * 后到冻结或追溯更正撤销未执行候选，已执行则生成事实冲突与升级记录；
 * 执行账逐对象记录操作号、请求、确认、拒绝与结果未知。
 */
@Service
public class DisposalService {

    private final DisposalPlanRepository planRepository;
    private final DisposalCandidateRepository candidateRepository;
    private final ExecutionLedgerRepository ledgerRepository;
    private final EscalationRecordRepository escalationRepository;
    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final WorldService worldService;
    private final ObligationCore core;
    private final ObligationLedgerService ledgerService;
    private final WatermarkService watermark;

    public DisposalService(DisposalPlanRepository planRepository,
                           DisposalCandidateRepository candidateRepository,
                           ExecutionLedgerRepository ledgerRepository,
                           EscalationRecordRepository escalationRepository,
                           RecordingObjectRepository recordingRepository,
                           RecordingSegmentRepository segmentRepository,
                           WorldService worldService,
                           ObligationCore core,
                           ObligationLedgerService ledgerService,
                           WatermarkService watermark) {
        this.planRepository = planRepository;
        this.candidateRepository = candidateRepository;
        this.ledgerRepository = ledgerRepository;
        this.escalationRepository = escalationRepository;
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.worldService = worldService;
        this.core = core;
        this.ledgerService = ledgerService;
        this.watermark = watermark;
    }

    // ---------------- 处置计划 ----------------

    @Transactional
    public Map<String, Object> createPlan(LocalDate asOf, String route) {
        if (!"A".equals(route) && !"B".equals(route)) {
            throw ApiException.badRequest("route 必须是 A（截点遍历）或 B（义务账）");
        }
        long wm = watermark.now();
        WorldState world = worldService.worldAt(wm);
        List<RetentionRule> rules = worldService.allRules();
        List<Map<String, Object>> rulePins = rulePins(rules, wm);

        DisposalPlan plan = new DisposalPlan();
        plan.setId(UUID.randomUUID());
        plan.setCreatedSeq(watermark.next());
        plan.setAsOfDate(asOf);
        plan.setWatermark(wm);
        plan.setRoute(route);
        plan.setRulePins(rulePins);
        plan.setStatus(DisposalPlan.STATUS_OPEN);
        plan.setCreatedAt(Instant.now());
        planRepository.save(plan);

        EvalStats stats = new EvalStats();
        stats.eventsFolded = world.eventsFolded();
        List<Map<String, Object>> items = new ArrayList<>();
        for (RecordingObject rec : recordingRepository.findByStatus(RecordingObject.STATUS_ACTIVE)) {
            List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(rec.getId());
            RecordingEvaluation eval = "B".equals(route)
                    ? ledgerService.evaluateRecording(rec.getId(), segs, world, asOf, stats)
                    : core.evaluateRecording(rec.getId(), segs, world, rules, asOf, stats);
            DisposalCandidate c = new DisposalCandidate();
            c.setId(UUID.randomUUID());
            c.setPlanId(plan.getId());
            c.setRecordingId(rec.getId());
            c.setEligible(eval.eligible());
            c.setStatus(eval.eligible()
                    ? DisposalCandidate.STATUS_PENDING : DisposalCandidate.STATUS_BLOCKED);
            c.setBlockReasons(eval.blockReasons());
            c.setDependency(Map.of("watermark", wm, "route", route, "rulePins", rulePins));
            c.setCreatedAt(Instant.now());
            candidateRepository.save(c);
            items.add(candidateView(c));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", plan.getId());
        out.put("asOfDate", asOf);
        out.put("watermark", wm);
        out.put("route", route);
        out.put("rulePins", rulePins);
        out.put("items", items);
        out.put("evalCost", stats.toMap());
        return out;
    }

    private List<Map<String, Object>> rulePins(List<RetentionRule> rules, long wm) {
        return rules.stream()
                .filter(r -> r.getRegisteredSeq() <= wm)
                .sorted((a, b) -> {
                    int c = a.getRuleCode().compareTo(b.getRuleCode());
                    return c != 0 ? c : Integer.compare(a.getVersion(), b.getVersion());
                })
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("ruleId", r.getId());
                    m.put("ruleCode", r.getRuleCode());
                    m.put("version", r.getVersion());
                    m.put("status", r.getStatus());
                    return m;
                })
                .toList();
    }

    // ---------------- 模拟执行（提交前重新核验） ----------------

    @Transactional
    public Map<String, Object> execute(UUID candidateId, String simulate) {
        DisposalCandidate c = candidateRepository.findById(candidateId)
                .orElseThrow(() -> ApiException.notFound("候选不存在: " + candidateId));
        if (!DisposalCandidate.STATUS_PENDING.equals(c.getStatus())) {
            ExecutionLedger existing = ledgerRepository.findByCandidateId(candidateId).orElse(null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executed", false);
            out.put("candidateStatus", c.getStatus());
            out.put("message", "候选当前状态不可执行（幂等：不重复提交）");
            if (existing != null) {
                out.put("operationNo", existing.getOperationNo());
                out.put("finalResult", existing.getFinalResult());
            }
            return out;
        }
        DisposalPlan plan = planRepository.findById(c.getPlanId())
                .orElseThrow(() -> ApiException.notFound("计划不存在"));
        RecordingObject rec = recordingRepository.findById(c.getRecordingId())
                .orElseThrow(() -> ApiException.notFound("录音对象不存在"));

        // 提交前重新核验：用当前世界状态与当前规则，按计划 asOf 重估义务与保全
        WorldState worldNow = worldService.currentWorld();
        List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(rec.getId());
        RecordingEvaluation revalidation = core.evaluateRecording(
                rec.getId(), segs, worldNow, worldService.allRules(), plan.getAsOfDate(), new EvalStats());
        if (!revalidation.eligible()) {
            c.setStatus(DisposalCandidate.STATUS_REVOKED);
            c.setRevokeReason("提交前重新核验未通过: " + String.join("; ", revalidation.blockReasons()));
            c.setDecidedAt(Instant.now());
            candidateRepository.save(c);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executed", false);
            out.put("candidateStatus", c.getStatus());
            out.put("revokeReason", c.getRevokeReason());
            return out;
        }

        String operationNo = "OP-" + candidateId;
        ExecutionLedger existing = ledgerRepository.findByOperationNo(operationNo).orElse(null);
        if (existing != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("executed", false);
            out.put("operationNo", operationNo);
            out.put("finalResult", existing.getFinalResult());
            out.put("message", "相同操作号已提交，幂等返回不重复处置");
            return out;
        }

        ExecutionLedger row = new ExecutionLedger();
        row.setId(UUID.randomUUID());
        row.setOperationNo(operationNo);
        row.setCandidateId(candidateId);
        row.setRecordingId(rec.getId());
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("recordingId", rec.getId().toString());
        req.put("sourceObjectId", rec.getSourceObjectId());
        req.put("contentHash", rec.getContentHash());
        req.put("candidateId", candidateId.toString());
        req.put("planId", plan.getId().toString());
        req.put("asOfDate", plan.getAsOfDate().toString());
        req.put("revalidatedAtWatermark", worldNow.watermark());
        row.setRequestPayload(req);
        row.setSubmittedAt(Instant.now());
        row.setReceiptStatus(ExecutionLedger.RECEIPT_NONE);
        row.setFinalResult(ExecutionLedger.RESULT_PENDING);
        row.setDuplicateReceipts(0);
        ledgerRepository.save(row);

        String mode = simulate == null ? "CONFIRM" : simulate;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operationNo", operationNo);
        switch (mode) {
            case "CONFIRM" -> {
                applyReceipt(row, ExecutionLedger.RECEIPT_CONFIRMED,
                        Map.of("storage", "simulator", "mode", "CONFIRM"));
                out.put("executed", true);
                out.put("finalResult", row.getFinalResult());
            }
            case "REJECT" -> {
                applyReceipt(row, ExecutionLedger.RECEIPT_REJECTED,
                        Map.of("storage", "simulator", "mode", "REJECT", "reason", "存储平台拒绝"));
                out.put("executed", false);
                out.put("finalResult", row.getFinalResult());
            }
            case "TIMEOUT" -> {
                // 超时：不得计为成功，结果未知，等待回执或凭原操作号核对
                row.setReceiptStatus(ExecutionLedger.RECEIPT_UNKNOWN);
                row.setFinalResult(ExecutionLedger.RESULT_UNKNOWN);
                ledgerRepository.save(row);
                c.setStatus(DisposalCandidate.STATUS_SUBMITTED);
                candidateRepository.save(c);
                out.put("executed", false);
                out.put("finalResult", row.getFinalResult());
                out.put("message", "回执超时：结果未知（非成功），须凭原操作号核对");
            }
            default -> throw ApiException.badRequest("simulate 仅支持 CONFIRM / REJECT / TIMEOUT");
        }
        return out;
    }

    // ---------------- 存储回执端 ----------------

    @Transactional
    public Map<String, Object> receiveReceipt(String operationNo, String result,
                                              Map<String, Object> payload) {
        ExecutionLedger row = ledgerRepository.findByOperationNo(operationNo)
                .orElseThrow(() -> ApiException.notFound("原操作号不存在: " + operationNo));
        if (!ExecutionLedger.RECEIPT_CONFIRMED.equals(result)
                && !ExecutionLedger.RECEIPT_REJECTED.equals(result)) {
            throw ApiException.badRequest("回执结果仅支持 CONFIRMED / REJECTED");
        }
        String outcome = applyReceipt(row, result, payload == null ? Map.of() : payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operationNo", operationNo);
        out.put("outcome", outcome);
        out.put("receiptStatus", row.getReceiptStatus());
        out.put("finalResult", row.getFinalResult());
        out.put("duplicateReceipts", row.getDuplicateReceipts());
        return out;
    }

    /**
     * 应用回执：终态行收到相同回执 → 幂等忽略（不重复处置）；
     * 终态行收到矛盾回执 → 记录冲突升级；未终态 → 落定结果。
     */
    private String applyReceipt(ExecutionLedger row, String result, Map<String, Object> payload) {
        boolean confirmed = ExecutionLedger.RECEIPT_CONFIRMED.equals(result);
        String targetFinal = confirmed ? ExecutionLedger.RESULT_SUCCESS : ExecutionLedger.RESULT_FAILED;
        if (ExecutionLedger.RESULT_SUCCESS.equals(row.getFinalResult())
                || ExecutionLedger.RESULT_FAILED.equals(row.getFinalResult())) {
            if (row.getFinalResult().equals(targetFinal)) {
                row.setDuplicateReceipts(row.getDuplicateReceipts() + 1);
                ledgerRepository.save(row);
                return "DUPLICATE_IGNORED";
            }
            row.setDuplicateReceipts(row.getDuplicateReceipts() + 1);
            ledgerRepository.save(row);
            saveEscalation(EscalationRecord.KIND_RECEIPT_CONFLICT, row.getRecordingId(),
                    row.getCandidateId(), row.getOperationNo(),
                    Map.of("existingResult", row.getFinalResult(), "incomingResult", targetFinal,
                            "note", "终态回执与既有结果矛盾，保留冲突待核查"));
            return "CONFLICT_RECORDED";
        }
        row.setReceiptStatus(result);
        row.setReceiptPayload(payload);
        row.setReceiptReceivedAt(Instant.now());
        row.setFinalResult(targetFinal);
        ledgerRepository.save(row);

        DisposalCandidate c = candidateRepository.findById(row.getCandidateId()).orElse(null);
        if (c != null) {
            boolean wasRevoked = DisposalCandidate.STATUS_REVOKED.equals(c.getStatus());
            c.setStatus(confirmed ? DisposalCandidate.STATUS_EXECUTED
                    : DisposalCandidate.STATUS_REJECTED);
            c.setDecidedAt(Instant.now());
            candidateRepository.save(c);
            if (confirmed) {
                recordingRepository.findById(row.getRecordingId()).ifPresent(rec -> {
                    rec.setStatus(RecordingObject.STATUS_DISPOSED);
                    recordingRepository.save(rec);
                });
            }
            if (wasRevoked && confirmed) {
                saveEscalation(EscalationRecord.KIND_DISPOSAL_AFTER_REVOKE, row.getRecordingId(),
                        row.getCandidateId(), row.getOperationNo(),
                        Map.of("note", "候选已撤销后仍收到确认回执：处置事实与撤销状态冲突，升级核查"));
            }
        }
        return confirmed ? "CONFIRMED_APPLIED" : "REJECTED_APPLIED";
    }

    // ---------------- 对账 ----------------

    /** 结果未知须用原操作号核对；核对本身不制造结果，只反映当前账目状态。 */
    @Transactional
    public Map<String, Object> reconcile(String operationNo) {
        ExecutionLedger row = ledgerRepository.findByOperationNo(operationNo)
                .orElseThrow(() -> ApiException.notFound("原操作号不存在: " + operationNo));
        row.setLastReconciledAt(Instant.now());
        ledgerRepository.save(row);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operationNo", row.getOperationNo());
        out.put("receiptStatus", row.getReceiptStatus());
        out.put("finalResult", row.getFinalResult());
        out.put("reconciledAt", row.getLastReconciledAt());
        out.put("message", ExecutionLedger.RESULT_UNKNOWN.equals(row.getFinalResult())
                ? "结果未知：尚未收到回执，保持 UNKNOWN（不计为成功）" : "账目已落定");
        return out;
    }

    // ---------------- 变更传播：撤销未执行候选 / 升级已执行 ----------------

    @Transactional(propagation = Propagation.REQUIRED)
    public void onWorldChange(ChangeImpact impact) {
        if (impact == null || impact.isEmpty()) {
            return;
        }
        WorldState worldNow = worldService.currentWorld();
        List<RetentionRule> rules = worldService.allRules();
        for (UUID recordingId : impact.recordingIds()) {
            List<DisposalCandidate> affected = candidateRepository.findByRecordingIdAndStatusIn(
                    recordingId, List.of(DisposalCandidate.STATUS_PENDING,
                            DisposalCandidate.STATUS_SUBMITTED,
                            DisposalCandidate.STATUS_EXECUTED));
            if (affected.isEmpty()) {
                continue;
            }
            RecordingObject rec = recordingRepository.findById(recordingId).orElse(null);
            if (rec == null) {
                continue;
            }
            List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(recordingId);
            for (DisposalCandidate c : affected) {
                DisposalPlan plan = planRepository.findById(c.getPlanId()).orElse(null);
                LocalDate asOf = plan != null ? plan.getAsOfDate() : LocalDate.now();
                RecordingEvaluation eval = core.evaluateRecording(
                        recordingId, segs, worldNow, rules, asOf, new EvalStats());
                if (eval.eligible()) {
                    continue;
                }
                if (DisposalCandidate.STATUS_EXECUTED.equals(c.getStatus())) {
                    // 已执行：生成事实冲突与升级记录，不伪称恢复了已删除原件
                    ExecutionLedger row = ledgerRepository.findByCandidateId(c.getId()).orElse(null);
                    saveEscalation(EscalationRecord.KIND_EXECUTED_DISPOSAL_CONFLICT, recordingId,
                            c.getId(), row != null ? row.getOperationNo() : null,
                            Map.of("trigger", impact.reason(),
                                    "blockReasonsNow", eval.blockReasons(),
                                    "note", "到处置执行后出现新的义务/保全事实：原件已删除，记录冲突并升级核查，不伪称恢复"));
                } else {
                    c.setStatus(DisposalCandidate.STATUS_REVOKED);
                    c.setRevokeReason(impact.reason() + " → " + String.join("; ", eval.blockReasons()));
                    c.setDecidedAt(Instant.now());
                    candidateRepository.save(c);
                }
            }
        }
    }

    private void saveEscalation(String kind, UUID recordingId, UUID candidateId,
                                String operationNo, Map<String, Object> details) {
        EscalationRecord esc = new EscalationRecord();
        esc.setId(UUID.randomUUID());
        esc.setKind(kind);
        esc.setRecordingId(recordingId);
        esc.setCandidateId(candidateId);
        esc.setOperationNo(operationNo);
        esc.setDetails(details);
        esc.setStatus("OPEN");
        esc.setCreatedAt(Instant.now());
        escalationRepository.save(esc);
    }

    public static Map<String, Object> candidateView(DisposalCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidateId", c.getId());
        m.put("planId", c.getPlanId());
        m.put("recordingId", c.getRecordingId());
        m.put("eligible", c.isEligible());
        m.put("status", c.getStatus());
        m.put("blockReasons", c.getBlockReasons());
        m.put("revokeReason", c.getRevokeReason());
        m.put("dependency", c.getDependency());
        m.put("createdAt", c.getCreatedAt());
        m.put("decidedAt", c.getDecidedAt());
        return m;
    }
}
