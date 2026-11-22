package com.example.retention.obligations;

import com.example.retention.graph.FactView;
import com.example.retention.graph.HoldView;
import com.example.retention.graph.LinkView;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.rules.RetentionRule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 义务求值核心：路线A（截点遍历派生）与路线B（义务账）共用同一套判定，
 * 保证同一截点得到相同保留结论与处置资格。
 *
 * 规则选择语义：对事实 F，规则版本 v 可见当且仅当
 *   v.registeredSeq <= 水位 且 F 生效日落入 v 适用区间 且 (v 追溯生效 或 v.registeredSeq <= F 最近变更水位)；
 * 同一 ruleCode 取满足条件的最高版本。起算事实未确认时给出未确定依据（PENDING），不提前到期。
 */
@Component
public class ObligationCore {

    public static final String STATE_RETAINED = "RETAINED";
    public static final String STATE_EXPIRED = "EXPIRED";
    public static final String STATE_PENDING = "PENDING";

    /** 派生片段的全部义务（不含 asOf 状态，供账载差分与求值共用）。 */
    public List<ObligationView> deriveObligations(RecordingSegment seg, WorldState world,
                                                  List<RetentionRule> allRules, EvalStats stats) {
        List<ObligationView> out = new ArrayList<>();
        List<LinkView> links = world.activeLinksOf(seg.getId());
        stats.linksWalked += links.size();
        for (LinkView link : links) {
            FactView fact = world.fact(link.factKey());
            if (fact == null || fact.revoked()) {
                continue;
            }
            stats.factsLoaded++;
            List<RetentionRule> applicable = selectRules(allRules, fact, world.watermark(), stats);
            for (RetentionRule rule : applicable) {
                if (!fact.confirmed()) {
                    out.add(new ObligationView(rule.getRuleCode(), rule.getVersion(), rule.getId(),
                            fact.factKey(), fact.factType(), fact.status(),
                            fact.effectiveDate(), null,
                            ObligationEntry.BASIS_UNDETERMINED, STATE_PENDING));
                } else {
                    LocalDate expiry = fact.effectiveDate().plusYears(rule.getRetentionYears());
                    out.add(new ObligationView(rule.getRuleCode(), rule.getVersion(), rule.getId(),
                            fact.factKey(), fact.factType(), fact.status(),
                            fact.effectiveDate(), expiry,
                            ObligationEntry.BASIS_RULE_CONFIRMED, null));
                }
            }
        }
        out.sort(Comparator.comparing(ObligationView::identity));
        return out;
    }

    /** 规则选择：见类注释的语义。 */
    public List<RetentionRule> selectRules(List<RetentionRule> allRules, FactView fact,
                                           long watermark, EvalStats stats) {
        Map<String, RetentionRule> bestByCode = new LinkedHashMap<>();
        for (RetentionRule v : allRules) {
            stats.rulesConsidered++;
            if (!v.getFactType().equals(fact.factType())) {
                continue;
            }
            if (v.getRegisteredSeq() > watermark) {
                continue;
            }
            if (!v.covers(fact.effectiveDate())) {
                continue;
            }
            if (!v.isRetroactive() && v.getRegisteredSeq() > fact.lastChangeSeq()) {
                continue;
            }
            RetentionRule cur = bestByCode.get(v.getRuleCode());
            if (cur == null || v.getVersion() > cur.getVersion()) {
                bestByCode.put(v.getRuleCode(), v);
            }
        }
        return new ArrayList<>(bestByCode.values());
    }

    /** 片段级求值：义务 + 保全 → 处置资格。 */
    public SegmentEvaluation evaluateSegment(RecordingSegment seg, WorldState world,
                                             List<RetentionRule> allRules, LocalDate asOf,
                                             EvalStats stats) {
        stats.segmentsVisited++;
        List<ObligationView> derived = deriveObligations(seg, world, allRules, stats);
        List<ObligationView> obligations = derived.stream()
                .map(o -> withState(o, asOf)).toList();
        List<HoldView> holds = coveringHolds(seg, world);
        List<String> reasons = blockReasons(seg, obligations, holds);
        boolean eligible = reasons.isEmpty();
        return new SegmentEvaluation(seg.getId(), seg.getRecordingId(), seg.getSeq(),
                obligations, holds, normalRetentionUntil(obligations), eligible, reasons);
    }

    /** 普通保留到期日：已确认义务的最晚到期日（未确定依据的义务不参与计算）。 */
    public LocalDate normalRetentionUntil(List<ObligationView> obligations) {
        return obligations.stream()
                .filter(o -> ObligationEntry.BASIS_RULE_CONFIRMED.equals(o.basis()))
                .map(ObligationView::expiryDate)
                .filter(d -> d != null)
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    public ObligationView withState(ObligationView o, LocalDate asOf) {
        if (ObligationEntry.BASIS_UNDETERMINED.equals(o.basis())) {
            return new ObligationView(o.ruleCode(), o.ruleVersion(), o.ruleId(), o.factKey(),
                    o.factType(), o.factStatus(), o.triggerDate(), null, o.basis(), STATE_PENDING);
        }
        String state = asOf.isBefore(o.expiryDate()) ? STATE_RETAINED : STATE_EXPIRED;
        return new ObligationView(o.ruleCode(), o.ruleVersion(), o.ruleId(), o.factKey(),
                o.factType(), o.factStatus(), o.triggerDate(), o.expiryDate(), o.basis(), state);
    }

    /** 覆盖片段的未解除保全：SEGMENT / RECORDING / FACT / CASE 四级作用域。 */
    public List<HoldView> coveringHolds(RecordingSegment seg, WorldState world) {
        List<String> activeFactKeys = world.activeLinksOf(seg.getId()).stream()
                .map(LinkView::factKey).toList();
        List<String> caseIds = activeFactKeys.stream()
                .map(world::fact)
                .filter(f -> f != null && f.caseId() != null)
                .map(FactView::caseId).toList();
        List<HoldView> out = new ArrayList<>();
        for (HoldView h : world.allHolds()) {
            if (!h.active()) {
                continue;
            }
            boolean cover = switch (h.scopeType()) {
                case "SEGMENT" -> h.scopeRef().equals(seg.getId().toString());
                case "RECORDING" -> h.scopeRef().equals(seg.getRecordingId().toString());
                case "FACT" -> activeFactKeys.contains(h.scopeRef());
                case "CASE" -> caseIds.contains(h.scopeRef());
                default -> false;
            };
            if (cover) {
                out.add(h);
            }
        }
        return out;
    }

    public List<String> blockReasons(RecordingSegment seg, List<ObligationView> obligations,
                                     List<HoldView> holds) {
        List<String> reasons = new ArrayList<>();
        for (ObligationView o : obligations) {
            if (STATE_RETAINED.equals(o.state())) {
                reasons.add("义务未到期: rule=" + o.ruleCode() + " v" + o.ruleVersion()
                        + " fact=" + o.factKey() + " expiry=" + o.expiryDate());
            } else if (STATE_PENDING.equals(o.state())) {
                reasons.add("起算事实未确认: fact=" + o.factKey() + "（未确定依据，不提前到期）");
            }
        }
        for (HoldView h : holds) {
            reasons.add("保全未解除: " + h.holdKey() + " scope=" + h.scopeType() + "/" + h.scopeRef());
        }
        return reasons;
    }

    /** 整对象求值：全部片段均具备处置资格时整对象才是候选。 */
    public RecordingEvaluation evaluateRecording(UUID recordingId, List<RecordingSegment> segments,
                                                 WorldState world, List<RetentionRule> allRules,
                                                 LocalDate asOf, EvalStats stats) {
        List<SegmentEvaluation> segEvals = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (RecordingSegment seg : segments) {
            SegmentEvaluation se = evaluateSegment(seg, world, allRules, asOf, stats);
            segEvals.add(se);
            for (String r : se.blockReasons()) {
                reasons.add("seg#" + seg.getSeq() + " " + r);
            }
        }
        boolean eligible = segEvals.stream().allMatch(SegmentEvaluation::eligible);
        return new RecordingEvaluation(recordingId, eligible, segEvals, reasons);
    }
}
