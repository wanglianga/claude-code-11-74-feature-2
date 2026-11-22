package com.example.retention.privacy;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingObject;
import com.example.retention.ingest.RecordingObjectRepository;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 证据视图生成器：从不可变原转写按案件目的生成带遮蔽规则版本的证据视图，两条路线并存。
 *
 * <ul>
 *   <li>ON_DEMAND：调阅时即时从原转写生成（有生成等待），每次重新求值，不依赖物化缓存；</li>
 *   <li>MATERIALIZED：经批准的常用角色视图预先物化，FRESH 时重复调阅直接返回，
 *       规则修订、转写更正、识别修正或案件范围变化时准确失效（STALE），新下载被拦截。</li>
 * </ul>
 *
 * <p>不变量：两条路线都不删除唯一原证据（录音仅置 SUPERSEDED）；每个视图逐处回链字段位置
 * （segment/offset）、遮蔽规则版本与临时还原审批；遮蔽完整性在生成时核验，任何明文泄露即拒绝出视图。
 */
@Service
public class EvidenceViewService {

    private final EvidenceViewRepository viewRepository;
    private final EvidenceViewDownloadRepository downloadRepository;
    private final SensitiveEntityRepository entityRepository;
    private final SensitiveEntityMentionRepository mentionRepository;
    private final MaskingRuleRepository maskingRuleRepository;
    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final WorldService worldService;
    private final WatermarkService watermark;
    private final ScopeValidator scopeValidator;
    private final RevealApprovalService approvalService;
    private final ViewDownloadLedger downloadLedger;
    private final ObjectMapper canonicalMapper;

    public EvidenceViewService(EvidenceViewRepository viewRepository,
                               EvidenceViewDownloadRepository downloadRepository,
                               SensitiveEntityRepository entityRepository,
                               SensitiveEntityMentionRepository mentionRepository,
                               MaskingRuleRepository maskingRuleRepository,
                               RecordingObjectRepository recordingRepository,
                               RecordingSegmentRepository segmentRepository,
                               WorldService worldService,
                               WatermarkService watermark,
                               ScopeValidator scopeValidator,
                               RevealApprovalService approvalService,
                               ViewDownloadLedger downloadLedger) {
        this.viewRepository = viewRepository;
        this.downloadRepository = downloadRepository;
        this.entityRepository = entityRepository;
        this.mentionRepository = mentionRepository;
        this.maskingRuleRepository = maskingRuleRepository;
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.worldService = worldService;
        this.watermark = watermark;
        this.scopeValidator = scopeValidator;
        this.approvalService = approvalService;
        this.downloadLedger = downloadLedger;
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record ViewRequest(String role, String purpose, List<String> caseScope,
                              UUID recordingId, UUID revealApprovalId) {
    }

    public record DownloadRequest(String role, String purpose, List<String> caseScope) {
    }

    // ================= 按需生成 =================

    @Transactional
    public Map<String, Object> generateOnDemand(ViewRequest req) {
        scopeValidator.requireKnownRole(req.role());
        long wm = watermark.now();
        BuiltView built = build(req, wm);
        EvidenceView v = new EvidenceView();
        v.setId(UUID.randomUUID());
        v.setViewCode("OD-" + watermark.next() + "-" + req.recordingId());
        v.setRoute(EvidenceView.ROUTE_ON_DEMAND);
        v.setStatus(EvidenceView.STATUS_FRESH);
        applyBuilt(v, req, built, wm);
        v.setDownloadCount(0);
        viewRepository.save(v);
        return present(v, built, "ON_DEMAND 已从不可变原转写即时生成（未使用物化副本）");
    }

    // ================= 预物化 =================

    @Transactional
    public Map<String, Object> materialize(ViewRequest req) {
        scopeValidator.requireKnownRole(req.role());
        long wm = watermark.now();
        BuiltView built = build(req, wm);
        String code = viewCode(EvidenceView.ROUTE_MATERIALIZED, req, built.rulePins);
        EvidenceView v = viewRepository.findByViewCode(code).orElse(null);
        String note;
        if (v != null && EvidenceView.STATUS_FRESH.equals(v.getStatus())) {
            note = "物化视图 FRESH，重复调阅直接复用（未重新生成）";
            return present(v, null, note);
        }
        if (v == null) {
            v = new EvidenceView();
            v.setId(UUID.randomUUID());
            v.setViewCode(code);
            v.setRoute(EvidenceView.ROUTE_MATERIALIZED);
            v.setDownloadCount(0);
            note = "常用角色视图已预先物化（FRESH）";
        } else {
            note = "失影视图已按当前规则/转写/范围重新物化（FRESH）";
        }
        v.setStatus(EvidenceView.STATUS_FRESH);
        v.setInvalidateReason(null);
        v.setInvalidatedAt(null);
        applyBuilt(v, req, built, wm);
        viewRepository.save(v);
        return present(v, built, note);
    }

    private void applyBuilt(EvidenceView v, ViewRequest req, BuiltView built, long wm) {
        v.setRecordingId(req.recordingId());
        v.setRequesterRole(req.role());
        v.setPurpose(req.purpose());
        v.setCaseScope(req.caseScope());
        v.setWatermark(wm);
        v.setRulePins(built.rulePins);
        v.setEntityPins(built.entityPins);
        v.setSegmentFingerprints(built.segmentFingerprints);
        v.setScopeSignature(built.scopeSignature);
        v.setMaskedContent(built.maskedContent);
        v.setContentHash(built.contentHash);
        v.setRevealApprovalId(req.revealApprovalId());
        v.setCreatedSeq(watermark.next());
        v.setCreatedAt(Instant.now());
    }

    // ================= 视图构建（遮蔽 + 回链 + 完整性核验） =================

    private record BuiltView(List<Map<String, Object>> rulePins, List<Map<String, Object>> entityPins,
                             List<Map<String, Object>> segmentFingerprints, String scopeSignature,
                             List<Map<String, Object>> maskedContent, String contentHash,
                             Map<String, Object> completeness, List<Map<String, Object>> exclusions,
                             Set<UUID> revealedEntityIds) {
    }

    private BuiltView build(ViewRequest req, long wm) {
        RecordingObject rec = recordingRepository.findById(req.recordingId())
                .orElseThrow(() -> ApiException.notFound("录音不存在: " + req.recordingId()));
        if (!RecordingObject.STATUS_ACTIVE.equals(rec.getStatus())) {
            throw ApiException.badRequest("原转写已被新版本取代（状态 " + rec.getStatus()
                    + "）：新视图只能对当前 ACTIVE 版本生成，历史版本仅留证不可再出新视图（唯一原证据不删除）");
        }
        List<RecordingSegment> segments = segmentRepository.findByRecordingIdOrderBySeq(req.recordingId());
        if (segments.isEmpty()) {
            throw ApiException.badRequest("录音无片段");
        }
        WorldState world = worldService.worldAt(wm);
        ScopeValidator.ScopeDecision decision =
                scopeValidator.evaluate(world, segments, req.role(), req.purpose(), req.caseScope());
        if (!decision.rolePurposeGranted()) {
            throw ApiException.forbidden("角色 " + req.role() + " 不可用用途 " + req.purpose());
        }
        Set<UUID> inScope = new LinkedHashSet<>(decision.inScopeSegmentIds());
        if (inScope.isEmpty()) {
            throw ApiException.forbidden("案件范围 " + req.caseScope()
                    + " 内无任何关联片段，拒绝生成视图（最小授权）");
        }

        // 固定当前生效遮蔽规则版本
        List<MaskingRule> rules = maskingRuleRepository
                .findByRegisteredSeqLessThanEqualOrderByRuleCodeAscVersionAsc(wm).stream()
                .collect(java.util.stream.Collectors.groupingBy(MaskingRule::getRuleCode,
                        java.util.stream.Collectors.maxBy(Comparator.comparingInt(MaskingRule::getVersion))))
                .values().stream().flatMap(java.util.Optional::stream).toList();
        if (rules.isEmpty()) {
            throw ApiException.badRequest("尚无生效遮蔽规则，无法生成证据视图");
        }
        Map<String, MaskingRule> ruleByType = new LinkedHashMap<>();
        List<Map<String, Object>> rulePins = new ArrayList<>();
        for (MaskingRule r : rules) {
            rulePins.add(MaskingRuleService.pin(r));
            for (String type : r.getRuleConfig().keySet()) {
                ruleByType.putIfAbsent(type, r);
            }
        }

        // 当前 ACTIVE 实体（registeredSeq ≤ 水位）及其提及，仅保留在 scope 内片段上的提及
        List<SensitiveEntity> activeEntities = entityRepository
                .findByRecordingIdAndStatusOrderByRegisteredSeq(req.recordingId(), SensitiveEntity.STATUS_ACTIVE)
                .stream().filter(e -> e.getRegisteredSeq() <= wm).toList();
        Map<UUID, SensitiveEntity> entityById = new LinkedHashMap<>();
        activeEntities.forEach(e -> entityById.put(e.getId(), e));
        List<SensitiveEntityMention> allMentions = mentionRepository
                .findByEntityIdInOrderByMentionSeq(new ArrayList<>(entityById.keySet()));
        Map<UUID, List<SensitiveEntityMention>> mentionsBySegment = new LinkedHashMap<>();
        for (SensitiveEntityMention m : allMentions) {
            if (inScope.contains(m.getSegmentId())) {
                mentionsBySegment.computeIfAbsent(m.getSegmentId(), k -> new ArrayList<>()).add(m);
            }
        }

        // 临时还原审批核验（如有）：还原集合以审批授权的最小实体集合为准
        Set<UUID> revealed = new LinkedHashSet<>();
        RevealApproval approval = null;
        if (req.revealApprovalId() != null) {
            approval = approvalService.findById(req.revealApprovalId());
            Set<UUID> approvedEntityIds = approval.getEntityIds().stream()
                    .map(UUID::fromString).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            approvalService.requireValid(req.revealApprovalId(), req.role(), req.purpose(),
                    req.caseScope(), req.recordingId(), approvedEntityIds);
            revealed = approvedEntityIds.stream()
                    .filter(entityById.keySet()::contains)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        List<Map<String, Object>> entityPins = activeEntities.stream()
                .map(e -> Map.<String, Object>of("entityId", e.getId().toString(), "entityType", e.getEntityType(),
                        "revision", e.getRevision(), "registeredSeq", e.getRegisteredSeq()))
                .toList();
        List<Map<String, Object>> fingerprints = segments.stream()
                .map(s -> Map.<String, Object>of("segmentId", s.getId().toString(), "segmentSeq", s.getSeq(),
                        "contentHash", s.getContentHash()))
                .toList();
        String scopeSig = ScopeValidator.scopeSignature(req.caseScope());

        List<Map<String, Object>> maskedContent = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        for (RecordingSegment seg : segments) {
            if (!inScope.contains(seg.getId())) {
                continue;
            }
            String original = seg.getTranscript() == null ? "" : seg.getTranscript();
            List<SensitiveEntityMention> segMentions = mentionsBySegment
                    .getOrDefault(seg.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(SensitiveEntityMention::getStartOffset))
                    .toList();
            StringBuilder masked = new StringBuilder(original);
            List<Map<String, Object>> maskRecords = new ArrayList<>();
            // 逆序替换以保持偏移有效
            for (int i = segMentions.size() - 1; i >= 0; i--) {
                SensitiveEntityMention m = segMentions.get(i);
                SensitiveEntity e = entityById.get(m.getEntityId());
                String covered = original.substring(m.getStartOffset(), m.getEndOffset());
                boolean isRevealed = revealed.contains(m.getEntityId());
                String maskedText;
                Map<String, Object> rulePin = null;
                if (isRevealed) {
                    maskedText = covered; // 审批授权内临时还原，保留明文
                } else {
                    MaskingRule rule = ruleByType.get(e.getEntityType());
                    if (rule == null) {
                        throw ApiException.badRequest("实体类型 " + e.getEntityType() + " 无遮蔽规则覆盖");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typeCfg = (Map<String, Object>) rule.getRuleConfig().get(e.getEntityType());
                    maskedText = MaskingActions.mask(covered, typeCfg);
                    rulePin = MaskingRuleService.pin(rule);
                    masked.replace(m.getStartOffset(), m.getEndOffset(), maskedText);
                }
                Map<String, Object> rec2 = new LinkedHashMap<>();
                rec2.put("mentionId", m.getId().toString());
                rec2.put("entityId", e.getId().toString());
                rec2.put("entityType", e.getEntityType());
                rec2.put("entityRevision", e.getRevision());
                rec2.put("segmentId", seg.getId().toString());
                rec2.put("segmentSeq", seg.getSeq());
                rec2.put("startOffset", m.getStartOffset());
                rec2.put("endOffset", m.getEndOffset());
                rec2.put("matchMode", m.getMatchMode());
                rec2.put("coveredText", covered);
                rec2.put("maskedText", maskedText);
                rec2.put("revealed", isRevealed);
                rec2.put("rule", rulePin);
                rec2.put("revealApprovalId", approval == null ? null : approval.getId().toString());
                rec2.put("approvalNo", approval == null ? null : approval.getApprovalNo());
                maskRecords.add(rec2);
            }
            String maskedStr = masked.toString();
            // 遮蔽完整性：未还原实体的原文片段与规范值不得出现在遮蔽文本中
            for (SensitiveEntityMention m : segMentions) {
                SensitiveEntity e = entityById.get(m.getEntityId());
                if (revealed.contains(m.getEntityId())) {
                    continue;
                }
                String covered = original.substring(m.getStartOffset(), m.getEndOffset());
                if (maskedStr.contains(covered)) {
                    leaks.add("segment " + seg.getSeq() + " 实体 " + e.getId() + " 原文未遮蔽: " + covered);
                }
                if (maskedStr.contains(e.getCanonicalValue())) {
                    leaks.add("segment " + seg.getSeq() + " 实体 " + e.getId() + " 规范值明文泄露");
                }
            }
            Map<String, Object> segView = new LinkedHashMap<>();
            segView.put("segmentId", seg.getId().toString());
            segView.put("segmentSeq", seg.getSeq());
            segView.put("originalLength", original.length());
            segView.put("maskedTranscript", maskedStr);
            segView.put("masks", maskRecords.stream()
                    .sorted(Comparator.comparingInt(r -> (Integer) r.get("startOffset"))).toList());
            maskedContent.add(segView);
        }
        if (!leaks.isEmpty()) {
            throw ApiException.conflict("遮蔽完整性核验失败，拒绝出视图: " + leaks);
        }
        Map<String, Object> completeness = new LinkedHashMap<>();
        completeness.put("checked", true);
        completeness.put("inScopeSegments", inScope.size());
        completeness.put("maskedMentions", maskedContent.stream().mapToInt(c -> ((List<?>) c.get("masks")).size()).sum());
        completeness.put("revealedMentions", maskedContent.stream()
                .flatMap(c -> ((List<Map<String, Object>>) c.get("masks")).stream())
                .filter(m -> Boolean.TRUE.equals(m.get("revealed"))).count());
        completeness.put("leaks", leaks);

        String contentHash = hash(maskedContent);
        return new BuiltView(rulePins, entityPins, fingerprints, scopeSig, maskedContent,
                contentHash, completeness, decision.exclusions(), revealed);
    }

    // ================= 失效（事件驱动 + 下载前防御性复核） =================

    @org.springframework.context.event.EventListener
    @Transactional
    public void onMaskingRuleChanged(PrivacyEvents.MaskingRuleChanged ev) {
        for (EvidenceView v : viewRepository.findByRouteAndStatus(
                EvidenceView.ROUTE_MATERIALIZED, EvidenceView.STATUS_FRESH)) {
            boolean pinned = v.getRulePins().stream()
                    .anyMatch(p -> ev.ruleCode().equals(p.get("ruleCode")));
            if (pinned) {
                markStale(v, "MASKING_RULE_REVISED: 遮蔽规则 " + ev.ruleCode()
                        + " 已修订至 v" + ev.newVersion() + "（水位 " + ev.registeredSeq() + "）");
            }
        }
    }

    @org.springframework.context.event.EventListener
    @Transactional
    public void onEntityCorrected(PrivacyEvents.SensitiveEntityCorrected ev) {
        for (EvidenceView v : viewRepository.findByRouteAndStatus(
                EvidenceView.ROUTE_MATERIALIZED, EvidenceView.STATUS_FRESH)) {
            boolean pinned = v.getRecordingId().equals(ev.recordingId()) && v.getEntityPins().stream()
                    .anyMatch(p -> ev.entityId().toString().equals(p.get("entityId")));
            if (pinned) {
                markStale(v, "ENTITY_REVISED: 敏感实体 " + ev.entityId()
                        + " 经历识别修正至 rev" + ev.newRevision());
            }
        }
    }

    @org.springframework.context.event.EventListener
    @Transactional
    public void onRecordingSuperseded(PrivacyEvents.RecordingSuperseded ev) {
        for (EvidenceView v : viewRepository.findByRecordingIdOrderByCreatedSeq(ev.oldRecordingId())) {
            if (EvidenceView.STATUS_FRESH.equals(v.getStatus())) {
                markStale(v, "TRANSCRIPT_CORRECTED: 原转写已被新版本取代（新录音 "
                        + ev.newRecordingId() + "，水位 " + ev.newSeq() + "），唯一原证据未删除");
            }
        }
    }

    private void markStale(EvidenceView v, String reason) {
        v.setStatus(EvidenceView.STATUS_STALE);
        v.setInvalidateReason(reason);
        v.setInvalidatedAt(Instant.now());
        viewRepository.save(v);
    }

    // ================= 下载（拦截 + 留痕） =================

    @Transactional
    public Map<String, Object> download(UUID viewId, DownloadRequest req) {
        EvidenceView v = viewRepository.findById(viewId)
                .orElseThrow(() -> ApiException.notFound("证据视图不存在: " + viewId));
        scopeValidator.requireKnownRole(req.role());
        String block = checkDownloadable(v, req);
        long downloadSeq;
        if (block != null) {
            // 独立事务落留痕：拦截异常回滚主事务也不影响审计记录
            EvidenceViewDownload ledger = downloadLedger.record(viewId, v.getRoute(), false,
                    req.role(), req.purpose(), block);
            downloadSeq = ledger.getSeq();
            throw ApiException.forbidden("新下载被拦截: " + block);
        }
        EvidenceViewDownload ledger = downloadLedger.record(viewId, v.getRoute(), true,
                req.role(), req.purpose(), "OK");
        downloadSeq = ledger.getSeq();
        v.setDownloadCount(v.getDownloadCount() + 1);
        viewRepository.save(v);
        Map<String, Object> out = present(v, null, "下载已通过当前授权与有效性核验");
        out.put("downloadSeq", downloadSeq);
        return out;
    }

    /** 返回 null 表示放行，否则为拦截原因。 */
    private String checkDownloadable(EvidenceView v, DownloadRequest req) {
        if (EvidenceView.ROUTE_MATERIALIZED.equals(v.getRoute())
                && !EvidenceView.STATUS_FRESH.equals(v.getStatus())) {
            return "视图状态 " + v.getStatus() + "（" + v.getInvalidateReason() + "），须重新物化";
        }
        if (!v.getRequesterRole().equals(req.role()) || !v.getPurpose().equals(req.purpose())) {
            return "角色/用途与视图不一致";
        }
        // 案件范围收窄：视图范围必须仍是当前范围的子集
        Set<String> current = new LinkedHashSet<>(req.caseScope() == null ? List.of() : req.caseScope());
        if (!current.containsAll(v.getCaseScope())) {
            return "CASE_SCOPE_NARROWED: 视图案件范围 " + v.getCaseScope()
                    + " 超出当前授权范围 " + current;
        }
        // 转写更正防御性复核：片段指纹须与当前片段一致
        List<RecordingSegment> segments = segmentRepository.findByRecordingIdOrderBySeq(v.getRecordingId());
        Map<String, String> currentHash = segments.stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.getId().toString(),
                        RecordingSegment::getContentHash, (a, b) -> a));
        for (Map<String, Object> fp : v.getSegmentFingerprints()) {
            String now = currentHash.get(fp.get("segmentId"));
            if (now == null || !now.equals(fp.get("contentHash"))) {
                return "TRANSCRIPT_CORRECTED: 片段 " + fp.get("segmentId") + " 指纹变化，原转写已更正";
            }
        }
        // 规则版本防御性复核：绑定的规则配置指纹须仍是当前生效版本
        long wm = watermark.now();
        Map<String, String> activeHashes = maskingRuleRepository
                .findByRegisteredSeqLessThanEqualOrderByRuleCodeAscVersionAsc(wm).stream()
                .collect(java.util.stream.Collectors.groupingBy(MaskingRule::getRuleCode,
                        java.util.stream.Collectors.maxBy(Comparator.comparingInt(MaskingRule::getVersion))))
                .values().stream().flatMap(java.util.Optional::stream)
                .collect(java.util.stream.Collectors.toMap(MaskingRule::getRuleCode,
                        MaskingRule::getConfigHash, (a, b) -> a));
        for (Map<String, Object> pin : v.getRulePins()) {
            String active = activeHashes.get(pin.get("ruleCode"));
            if (active == null || !active.equals(pin.get("configHash"))) {
                return "MASKING_RULE_REVISED: 规则 " + pin.get("ruleCode") + " 已有新版本，视图绑定已过时";
            }
        }
        // 临时还原审批仍有效（惰性过期会在内部落账并使视图失效）；
        // 下载只核验审批在当前请求上下文仍有效，还原实体集合在生成时已按最小授权约束。
        if (v.getRevealApprovalId() != null) {
            try {
                approvalService.requireValid(v.getRevealApprovalId(), req.role(), req.purpose(),
                        req.caseScope(), v.getRecordingId(), Set.of());
            } catch (ApiException ex) {
                return "REVEAL_APPROVAL_INVALID: " + ex.getMessage();
            }
        }
        return null;
    }

    // ================= 查询与辅助 =================

    @Transactional(readOnly = true)
    public List<EvidenceView> listViews() {
        return viewRepository.findAll();
    }

    @Transactional(readOnly = true)
    public EvidenceView requireView(UUID id) {
        return viewRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("证据视图不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<EvidenceViewDownload> downloads(UUID viewId) {
        return downloadRepository.findByViewIdOrderBySeq(viewId);
    }

    @Transactional(readOnly = true)
    public List<EvidenceViewDownload> allDownloads() {
        return downloadRepository.findAllByOrderBySeq();
    }

    private String viewCode(String route, ViewRequest req, List<Map<String, Object>> rulePins) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("route", route);
        basis.put("recordingId", req.recordingId().toString());
        basis.put("role", req.role());
        basis.put("purpose", req.purpose());
        basis.put("scope", ScopeValidator.scopeSignature(req.caseScope()));
        basis.put("rules", rulePins.stream().map(p -> p.get("ruleCode") + "@v" + p.get("version")).sorted().toList());
        basis.put("revealApprovalId", req.revealApprovalId() == null ? "" : req.revealApprovalId().toString());
        return route + "-" + hash(basis);
    }

    private Map<String, Object> present(EvidenceView v, BuiltView built, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewId", v.getId().toString());
        out.put("viewCode", v.getViewCode());
        out.put("route", v.getRoute());
        out.put("status", v.getStatus());
        out.put("recordingId", v.getRecordingId().toString());
        out.put("role", v.getRequesterRole());
        out.put("purpose", v.getPurpose());
        out.put("caseScope", v.getCaseScope());
        out.put("watermark", v.getWatermark());
        out.put("rulePins", v.getRulePins());
        out.put("entityPins", v.getEntityPins());
        out.put("segmentFingerprints", v.getSegmentFingerprints());
        out.put("scopeSignature", v.getScopeSignature());
        out.put("contentHash", v.getContentHash());
        out.put("revealApprovalId", v.getRevealApprovalId());
        out.put("invalidateReason", v.getInvalidateReason());
        out.put("downloadCount", v.getDownloadCount());
        out.put("maskedContent", v.getMaskedContent());
        out.put("maskCompleteness", built == null ? null : built.completeness());
        out.put("exclusions", built == null ? List.of() : built.exclusions());
        out.put("note", note);
        return out;
    }

    private String hash(Object o) {
        try {
            String canonical = canonicalMapper.writeValueAsString(o);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("无法计算内容指纹", ex);
        }
    }
}
