package com.example.retention.access;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.graph.FactView;
import com.example.retention.graph.LinkView;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.FieldClassification;
import com.example.retention.ingest.FieldClassificationRepository;
import com.example.retention.ingest.RecordingObject;
import com.example.retention.ingest.RecordingObjectRepository;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import com.example.retention.rules.RetentionRule;
import com.example.retention.rules.RetentionRuleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调阅求值器：对已提供字段分类按角色、案件范围与用途投影，
 * 返回允许片段、字段及拒绝理由，并固定本次使用的证据水位、关系与规则版本。
 * 历史调阅按原快照复演；再次下载材料仍须通过当前授权。
 */
@Service
public class AccessService {

    private final AccessSnapshotRepository snapshotRepository;
    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final FieldClassificationRepository fieldRepository;
    private final RetentionRuleRepository ruleRepository;
    private final WorldService worldService;
    private final WatermarkService watermark;
    private final AccessPolicy policy;

    public AccessService(AccessSnapshotRepository snapshotRepository,
                         RecordingObjectRepository recordingRepository,
                         RecordingSegmentRepository segmentRepository,
                         FieldClassificationRepository fieldRepository,
                         RetentionRuleRepository ruleRepository,
                         WorldService worldService,
                         WatermarkService watermark,
                         AccessPolicy policy) {
        this.snapshotRepository = snapshotRepository;
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.fieldRepository = fieldRepository;
        this.ruleRepository = ruleRepository;
        this.worldService = worldService;
        this.watermark = watermark;
        this.policy = policy;
    }

    public record AccessRequest(String role, String purpose, List<String> caseScope,
                                LocalDate asOf, List<UUID> recordingIds) {
    }

    // ---------------- 求值与快照 ----------------

    @Transactional
    public Map<String, Object> evaluateAndSnapshot(AccessRequest req) {
        validate(req);
        long wm = watermark.now();
        WorldState world = worldService.worldAt(wm);
        List<RetentionRule> currentRules = currentRules(worldService.rulesAt(wm));
        Map<String, Object> result = computeProjection(req, world, currentRules);

        AccessSnapshot snap = new AccessSnapshot();
        snap.setId(UUID.randomUUID());
        snap.setCreatedSeq(watermark.next());
        snap.setRequesterRole(req.role());
        snap.setPurpose(req.purpose());
        snap.setCaseScope(req.caseScope());
        snap.setAsOfDate(req.asOf() != null ? req.asOf() : LocalDate.now());
        snap.setWatermark(wm);
        snap.setRulePins(rulePins(currentRules));
        snap.setResult(result);
        snap.setCreatedAt(Instant.now());
        snapshotRepository.save(snap);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snap.getId());
        out.put("watermark", wm);
        out.put("rulePins", snap.getRulePins());
        out.put("result", result);
        return out;
    }

    private void validate(AccessRequest req) {
        if (!policy.knownRole(req.role())) {
            throw ApiException.forbidden("未知角色: " + req.role());
        }
        if (req.caseScope() == null) {
            throw ApiException.badRequest("案件范围 caseScope 不能为空（可为空数组）");
        }
    }

    /** 当前生效规则：每个 ruleCode 取水位内最高版本。 */
    private List<RetentionRule> currentRules(List<RetentionRule> visible) {
        Map<String, RetentionRule> best = new LinkedHashMap<>();
        for (RetentionRule r : visible) {
            RetentionRule cur = best.get(r.getRuleCode());
            if (cur == null || r.getVersion() > cur.getVersion()) {
                best.put(r.getRuleCode(), r);
            }
        }
        return new ArrayList<>(best.values());
    }

    private List<Map<String, Object>> rulePins(List<RetentionRule> rules) {
        return rules.stream()
                .sorted(Comparator.comparing(RetentionRule::getRuleCode)
                        .thenComparingInt(RetentionRule::getVersion))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("ruleId", r.getId().toString());
                    m.put("ruleCode", r.getRuleCode());
                    m.put("version", r.getVersion());
                    return m;
                })
                .toList();
    }

    /**
     * 投影核心：给定世界截点与规则集合，按角色/案件范围/用途计算允许片段与字段。
     * 对同一（世界, 规则, 请求）完全确定，复演可逐字节重现。
     */
    private Map<String, Object> computeProjection(AccessRequest req, WorldState world,
                                                  List<RetentionRule> rules) {
        Set<String> purposeFields = new TreeSet<>();
        for (RetentionRule r : rules) {
            if (r.getPurpose().equals(req.purpose())) {
                purposeFields.addAll(r.getFieldSet());
            }
        }
        boolean purposeGranted = policy.allowsPurpose(req.role(), req.purpose());

        List<RecordingObject> recordings = recordingRepository
                .findByStatus(RecordingObject.STATUS_ACTIVE).stream()
                .filter(r -> req.recordingIds() == null || req.recordingIds().contains(r.getId()))
                .sorted(Comparator.comparingLong(RecordingObject::getSeq))
                .toList();

        List<Map<String, Object>> allowed = new ArrayList<>();
        List<Map<String, Object>> denied = new ArrayList<>();
        for (RecordingObject rec : recordings) {
            List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(rec.getId());
            for (RecordingSegment seg : segs) {
                Map<String, Object> segDeny = new LinkedHashMap<>();
                segDeny.put("segmentId", seg.getId().toString());
                segDeny.put("recordingId", rec.getId().toString());
                segDeny.put("segmentSeq", seg.getSeq());
                List<String> reasons = new ArrayList<>();

                if (!purposeGranted) {
                    reasons.add("ROLE_PURPOSE_DENIED: 角色 " + req.role()
                            + " 不可用用途 " + req.purpose());
                }
                List<LinkView> links = world.activeLinksOf(seg.getId());
                List<FactView> facts = links.stream()
                        .map(l -> world.fact(l.factKey()))
                        .filter(f -> f != null && !f.revoked())
                        .toList();
                if (links.isEmpty()) {
                    reasons.add("NO_ACTIVE_LINK: 片段与任何业务事实均无显式关联（不按账户归并）");
                } else {
                    Set<String> factCases = new LinkedHashSet<>();
                    facts.stream().map(FactView::caseId).filter(java.util.Objects::nonNull)
                            .forEach(factCases::add);
                    boolean inScope = factCases.stream().anyMatch(req.caseScope()::contains);
                    if (!inScope) {
                        reasons.add("OUT_OF_CASE_SCOPE: 片段关联案件 " + factCases
                                + " 不在调阅范围 " + req.caseScope());
                    }
                }
                if (!reasons.isEmpty()) {
                    segDeny.put("reasons", reasons);
                    denied.add(segDeny);
                    continue;
                }
                List<FieldClassification> fields = fieldRepository.findBySegmentId(seg.getId());
                List<String> allowedFields = new ArrayList<>();
                List<Map<String, String>> deniedFields = new ArrayList<>();
                for (FieldClassification f : fields.stream()
                        .sorted(Comparator.comparing(FieldClassification::getFieldKey)).toList()) {
                    if (!policy.allowsClassification(req.role(), f.getClassification())) {
                        deniedFields.add(Map.of("field", f.getFieldKey(), "reason",
                                "CLASSIFICATION_DENIED: " + f.getClassification()));
                    } else if (!purposeFields.contains(f.getFieldKey())) {
                        deniedFields.add(Map.of("field", f.getFieldKey(), "reason",
                                "FIELD_NOT_IN_PURPOSE: 用途 " + req.purpose() + " 的字段集合不含该字段"));
                    } else {
                        allowedFields.add(f.getFieldKey());
                    }
                }
                if (allowedFields.isEmpty()) {
                    segDeny.put("reasons", List.of("NO_FIELD_PERMITTED: 无任何字段通过投影"));
                    segDeny.put("deniedFields", deniedFields);
                    denied.add(segDeny);
                    continue;
                }
                Map<String, Object> segAllow = new LinkedHashMap<>();
                segAllow.put("segmentId", seg.getId().toString());
                segAllow.put("recordingId", rec.getId().toString());
                segAllow.put("segmentSeq", seg.getSeq());
                segAllow.put("fields", allowedFields);
                segAllow.put("deniedFields", deniedFields);
                segAllow.put("facts", facts.stream().map(FactView::factKey).sorted().toList());
                allowed.add(segAllow);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", req.role());
        result.put("purpose", req.purpose());
        result.put("caseScope", req.caseScope());
        result.put("purposeFieldSet", new ArrayList<>(purposeFields));
        result.put("allowed", allowed);
        result.put("denied", denied);
        return result;
    }

    // ---------------- 历史复演 ----------------

    /**
     * 按原快照复演：折叠到快照水位、使用快照固定的规则版本重算，
     * 之后到达的授权或事实修订不影响当时判断。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> replay(UUID snapshotId) {
        AccessSnapshot snap = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> ApiException.notFound("快照不存在: " + snapshotId));
        WorldState worldAtThen = worldService.worldAt(snap.getWatermark());
        List<RetentionRule> pinnedRules = snap.getRulePins().stream()
                .map(pin -> ruleRepository.findById(UUID.fromString(pin.get("ruleId").toString()))
                        .orElseThrow(() -> ApiException.notFound("固定规则版本缺失: " + pin)))
                .toList();
        AccessRequest req = new AccessRequest(snap.getRequesterRole(), snap.getPurpose(),
                snap.getCaseScope(), snap.getAsOfDate(), null);
        Map<String, Object> replayed = computeProjection(req, worldAtThen, pinnedRules);
        boolean matches = replayed.equals(snap.getResult());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snapshotId);
        out.put("watermark", snap.getWatermark());
        out.put("matchesOriginal", matches);
        out.put("original", snap.getResult());
        out.put("replayed", replayed);
        return out;
    }

    // ---------------- 再次下载（须过当前授权） ----------------

    public record DownloadRequest(String role, String purpose, List<String> caseScope) {
    }

    public record DownloadResult(boolean granted, Map<String, Object> body) {
    }

    /**
     * 再次下载材料：历史快照固定当时判断，但材料放行必须重新通过当前授权，
     * 且只放行快照允许内容与当前授权的交集。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public DownloadResult download(UUID snapshotId, DownloadRequest req) {
        AccessSnapshot snap = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> ApiException.notFound("快照不存在: " + snapshotId));
        if (!policy.knownRole(req.role())) {
            throw ApiException.forbidden("未知角色: " + req.role());
        }
        // 当前授权：以当前水位重算
        long wmNow = watermark.now();
        WorldState worldNow = worldService.worldAt(wmNow);
        List<RetentionRule> currentRules = currentRules(worldService.rulesAt(wmNow));
        AccessRequest nowReq = new AccessRequest(req.role(), req.purpose(), req.caseScope(),
                LocalDate.now(), null);
        Map<String, Object> nowProjection = computeProjection(nowReq, worldNow, currentRules);
        Map<String, List<String>> nowAllowedFields = new LinkedHashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) nowProjection.get("allowed")) {
            nowAllowedFields.put(a.get("segmentId").toString(), (List<String>) a.get("fields"));
        }

        List<Map<String, Object>> material = new ArrayList<>();
        List<Map<String, Object>> exclusions = new ArrayList<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) snap.getResult().get("allowed")) {
            String segmentId = a.get("segmentId").toString();
            List<String> snapshotFields = (List<String>) a.get("fields");
            List<String> nowFields = nowAllowedFields.getOrDefault(segmentId, List.of());
            List<String> granted = snapshotFields.stream().filter(nowFields::contains).toList();
            if (granted.isEmpty()) {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("segmentId", segmentId);
                ex.put("reason", "当前授权不再覆盖该片段（案件范围收窄/角色或用途变化/关系已撤销）");
                exclusions.add(ex);
                continue;
            }
            RecordingSegment seg = segmentRepository.findById(UUID.fromString(segmentId)).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("segmentId", segmentId);
            m.put("fields", granted);
            m.put("transcript", seg != null && granted.contains("TRANSCRIPT") ? seg.getTranscript() : null);
            material.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snapshotId);
        body.put("evaluatedAtWatermark", wmNow);
        body.put("material", material);
        body.put("exclusions", exclusions);
        body.put("note", "再次下载已通过当前授权核验；历史快照本身未被改写");
        return new DownloadResult(!material.isEmpty(), body);
    }
}
