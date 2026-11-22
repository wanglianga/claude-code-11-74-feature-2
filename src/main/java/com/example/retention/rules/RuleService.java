package com.example.retention.rules;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.disposal.DisposalService;
import com.example.retention.graph.ChangeImpact;
import com.example.retention.graph.FactView;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingSegmentRepository;
import com.example.retention.obligations.LedgerMaintenanceLog;
import com.example.retention.obligations.ObligationLedgerService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 规则目录登记：适用事实、起算事件、保留期限、适用区间、用途与字段集合；支持版本与追溯。 */
@Service
public class RuleService {

    private final RetentionRuleRepository ruleRepository;
    private final WatermarkService watermark;
    private final WorldService worldService;
    private final RecordingSegmentRepository segmentRepository;
    private final ObligationLedgerService ledgerService;
    private final DisposalService disposalService;

    public RuleService(RetentionRuleRepository ruleRepository,
                       WatermarkService watermark,
                       WorldService worldService,
                       RecordingSegmentRepository segmentRepository,
                       ObligationLedgerService ledgerService,
                       DisposalService disposalService) {
        this.ruleRepository = ruleRepository;
        this.watermark = watermark;
        this.worldService = worldService;
        this.segmentRepository = segmentRepository;
        this.ledgerService = ledgerService;
        this.disposalService = disposalService;
    }

    public record RuleInput(String ruleCode, int version, String factType, String triggerEventType,
                            int retentionYears, LocalDate effectiveFrom, LocalDate effectiveTo,
                            String purpose, List<String> fieldSet, boolean retroactive) {
    }

    public record RuleOutcome(UUID ruleId, long registeredSeq, int affectedSegments,
                              int compensationsWritten, String message) {
    }

    @Transactional
    public RuleOutcome register(RuleInput in) {
        List<RetentionRule> existing = ruleRepository.findByRuleCodeOrderByVersion(in.ruleCode());
        if (existing.stream().anyMatch(r -> r.getVersion() == in.version())) {
            throw ApiException.conflict("规则版本已存在: " + in.ruleCode() + " v" + in.version());
        }
        if (!existing.isEmpty() && in.version() <= existing.get(existing.size() - 1).getVersion()) {
            throw ApiException.badRequest("规则版本必须递增");
        }
        if (in.retentionYears() <= 0) {
            throw ApiException.badRequest("保留期限必须为正数");
        }
        if (in.fieldSet() == null || in.fieldSet().isEmpty()) {
            throw ApiException.badRequest("字段集合不能为空");
        }

        RetentionRule rule = new RetentionRule();
        rule.setId(UUID.randomUUID());
        rule.setRegisteredSeq(watermark.next());
        rule.setRuleCode(in.ruleCode());
        rule.setVersion(in.version());
        rule.setFactType(in.factType());
        rule.setTriggerEventType(in.triggerEventType());
        rule.setRetentionYears(in.retentionYears());
        rule.setEffectiveFrom(in.effectiveFrom());
        rule.setEffectiveTo(in.effectiveTo());
        rule.setPurpose(in.purpose());
        rule.setFieldSet(in.fieldSet());
        rule.setRetroactive(in.retroactive());
        rule.setStatus(RetentionRule.STATUS_ACTIVE);
        rule.setCreatedAt(Instant.now());
        ruleRepository.save(rule);

        // 旧版本标记为被取代（历史复演仍可按固定版本引用）
        for (RetentionRule old : existing) {
            old.setStatus(RetentionRule.STATUS_SUPERSEDED);
            ruleRepository.save(old);
        }

        // 追溯生效：对适用区间内既有事实重放义务传播
        int compensations = 0;
        ChangeImpact impact = ChangeImpact.of("rule:" + in.ruleCode() + " v" + in.version());
        if (in.retroactive()) {
            WorldState world = worldService.currentWorld();
            for (FactView f : world.facts().values()) {
                if (!f.factType().equals(in.factType()) || f.revoked()) {
                    continue;
                }
                if (!rule.covers(f.effectiveDate())) {
                    continue;
                }
                world.links().values().stream()
                        .filter(l -> l.active() && l.factKey().equals(f.factKey()))
                        .forEach(l -> segmentRepository.findById(l.segmentId()).ifPresent(seg ->
                                impact.withSegment(seg.getId(), seg.getRecordingId())));
            }
        }
        if (!impact.isEmpty()) {
            LedgerMaintenanceLog log = ledgerService.maintain(impact,
                    "rule:" + rule.getRuleCode() + " v" + rule.getVersion());
            compensations = log.getEntriesAdded() + log.getEntriesVoided();
            disposalService.onWorldChange(impact);
        }
        return new RuleOutcome(rule.getId(), rule.getRegisteredSeq(),
                impact.segmentIds().size(), compensations,
                in.retroactive() ? "规则已登记并追溯重放" : "规则已登记（不追溯既有事实）");
    }

    public List<RetentionRule> list() {
        return ruleRepository.findAll();
    }
}
