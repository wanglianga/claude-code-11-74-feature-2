package com.example.retention.obligations;

import com.example.retention.common.WatermarkService;
import com.example.retention.graph.ChangeImpact;
import com.example.retention.graph.HoldView;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import com.example.retention.rules.RetentionRule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 路线B：不可变义务账 + 引用原证据的补偿记录。
 * 日常候选查询直接读账；关联撤销、追溯规则变更、事实更正后通过差分写入 VOID/RECTIFY 补偿，
 * 保证与路线A在同一截点结论一致。
 */
@Service
public class ObligationLedgerService {

    private final ObligationEntryRepository entryRepository;
    private final LedgerMaintenanceLogRepository logRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final ObligationCore core;
    private final WorldService worldService;
    private final WatermarkService watermark;

    public ObligationLedgerService(ObligationEntryRepository entryRepository,
                                   LedgerMaintenanceLogRepository logRepository,
                                   RecordingSegmentRepository segmentRepository,
                                   ObligationCore core,
                                   WorldService worldService,
                                   WatermarkService watermark) {
        this.entryRepository = entryRepository;
        this.logRepository = logRepository;
        this.segmentRepository = segmentRepository;
        this.core = core;
        this.worldService = worldService;
        this.watermark = watermark;
    }

    /**
     * 变更传播：对受影响片段重新派生义务并与账载差分，追加补偿记录（不重写历史条目）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public LedgerMaintenanceLog maintain(ChangeImpact impact, String triggerRef) {
        WorldState world = worldService.currentWorld();
        List<RetentionRule> rules = worldService.allRules();
        int added = 0;
        int voided = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (UUID segmentId : impact.segmentIds()) {
            RecordingSegment seg = segmentRepository.findById(segmentId).orElse(null);
            if (seg == null) {
                continue;
            }
            EvalStats stats = new EvalStats();
            List<ObligationView> derived = core.deriveObligations(seg, world, rules, stats);
            Map<String, ObligationView> derivedById = new LinkedHashMap<>();
            for (ObligationView d : derived) {
                derivedById.put(d.identity(), d);
            }
            List<ObligationEntry> active = entryRepository.activeEntries(segmentId, watermark.now());
            Map<String, ObligationEntry> activeById = new LinkedHashMap<>();
            for (ObligationEntry e : active) {
                activeById.put(identityOf(e), e);
            }
            // 失效补偿：账载有而派生无 → VOID
            for (Map.Entry<String, ObligationEntry> en : activeById.entrySet()) {
                if (!derivedById.containsKey(en.getKey())) {
                    ObligationEntry voidEntry = new ObligationEntry();
                    voidEntry.setId(UUID.randomUUID());
                    voidEntry.setEntrySeq(watermark.next());
                    voidEntry.setSegmentId(segmentId);
                    voidEntry.setRecordingId(seg.getRecordingId());
                    voidEntry.setBasis("NONE");
                    voidEntry.setEntryKind(ObligationEntry.KIND_VOID);
                    voidEntry.setInvalidatesId(en.getValue().getId());
                    voidEntry.setReason(impact.reason());
                    voidEntry.setCreatedAt(Instant.now());
                    entryRepository.save(voidEntry);
                    voided++;
                }
            }
            // 新增/更正补偿：派生有而账载无 → ORIGINAL（首次）或 RECTIFY
            boolean firstEver = entryRepository.findBySegmentIdOrderByEntrySeq(segmentId).isEmpty()
                    && active.isEmpty();
            for (Map.Entry<String, ObligationView> en : derivedById.entrySet()) {
                if (!activeById.containsKey(en.getKey())) {
                    ObligationView d = en.getValue();
                    ObligationEntry e = new ObligationEntry();
                    e.setId(UUID.randomUUID());
                    e.setEntrySeq(watermark.next());
                    e.setSegmentId(segmentId);
                    e.setRecordingId(seg.getRecordingId());
                    e.setRuleId(d.ruleId());
                    e.setRuleCode(d.ruleCode());
                    e.setRuleVersion(d.ruleVersion());
                    e.setFactKey(d.factKey());
                    e.setFactType(d.factType());
                    e.setTriggerDate(d.triggerDate());
                    e.setExpiryDate(d.expiryDate());
                    e.setBasis(d.basis());
                    e.setEntryKind(firstEver ? ObligationEntry.KIND_ORIGINAL
                            : ObligationEntry.KIND_RECTIFY);
                    e.setReason(impact.reason());
                    e.setCreatedAt(Instant.now());
                    entryRepository.save(e);
                    added++;
                }
            }
            if (!active.isEmpty() || !derived.isEmpty()) {
                Map<String, Object> segDetail = new LinkedHashMap<>();
                segDetail.put("segmentId", segmentId.toString());
                segDetail.put("derivedNow", derived.size());
                segDetail.put("activeBefore", active.size());
                details.add(segDetail);
            }
        }
        LedgerMaintenanceLog log = new LedgerMaintenanceLog();
        log.setId(UUID.randomUUID());
        log.setReason(impact.reason());
        log.setTriggerRef(triggerRef);
        log.setSegmentsScanned(impact.segmentIds().size());
        log.setEntriesAdded(added);
        log.setEntriesVoided(voided);
        Map<String, Object> detail = new HashMap<>();
        detail.put("segments", details);
        log.setDetail(detail);
        log.setCreatedAt(Instant.now());
        return logRepository.save(log);
    }

    public static String identityOf(ObligationEntry e) {
        return e.getRuleCode() + "#" + e.getRuleVersion() + "|" + e.getFactKey() + "|"
                + e.getTriggerDate() + "|" + e.getExpiryDate() + "|" + e.getBasis();
    }

    /** 路线B 片段求值：读账载有效义务（含补偿应用），保全按同一水位世界实时判定。 */
    @Transactional(readOnly = true)
    public SegmentEvaluation evaluateSegment(RecordingSegment seg, WorldState world,
                                             LocalDate asOf, EvalStats stats) {
        List<ObligationEntry> active = entryRepository.activeEntries(seg.getId(), world.watermark());
        stats.ledgerEntriesRead += active.size();
        List<ObligationEntry> all = entryRepository.findBySegmentIdOrderByEntrySeq(seg.getId());
        stats.voidCompensationsApplied += (int) all.stream()
                .filter(e -> ObligationEntry.KIND_VOID.equals(e.getEntryKind())
                        && e.getEntrySeq() <= world.watermark())
                .count();
        List<ObligationView> obligations = new ArrayList<>(active.stream()
                .map(e -> core.withState(
                        new ObligationView(e.getRuleCode(), e.getRuleVersion(), e.getRuleId(),
                                e.getFactKey(), e.getFactType(), null,
                                e.getTriggerDate(), e.getExpiryDate(), e.getBasis(), null),
                        asOf))
                .toList());
        obligations.sort((a, b) -> a.identity().compareTo(b.identity()));
        List<HoldView> holds = core.coveringHolds(seg, world);
        List<String> reasons = core.blockReasons(seg, obligations, holds);
        return new SegmentEvaluation(seg.getId(), seg.getRecordingId(), seg.getSeq(),
                obligations, holds, core.normalRetentionUntil(obligations),
                reasons.isEmpty(), reasons);
    }

    /** 路线B 整对象求值。 */
    @Transactional(readOnly = true)
    public RecordingEvaluation evaluateRecording(UUID recordingId, List<RecordingSegment> segments,
                                                 WorldState world, LocalDate asOf, EvalStats stats) {
        List<SegmentEvaluation> segEvals = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (RecordingSegment seg : segments) {
            SegmentEvaluation se = evaluateSegment(seg, world, asOf, stats);
            segEvals.add(se);
            for (String r : se.blockReasons()) {
                reasons.add("seg#" + seg.getSeq() + " " + r);
            }
        }
        boolean eligible = segEvals.stream().allMatch(SegmentEvaluation::eligible);
        return new RecordingEvaluation(recordingId, eligible, segEvals, reasons);
    }
}
