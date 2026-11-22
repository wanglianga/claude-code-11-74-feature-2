package com.example.retention.privacy;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.ingest.RecordingObject;
import com.example.retention.ingest.RecordingObjectRepository;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实体定位器：在不可变原转写上定位身份证号、银行卡号、住址、健康情况四类敏感实体，
 * 输出实体与其在片段文本中的字符级位置（segment/offset）。
 *
 * <p>能力要点：
 * <ul>
 *   <li>身份证号/银行卡号按数字模式定位，并对相邻片段边界做跨段缝合（实体被切成前后两段）；</li>
 *   <li>住址/健康情况按关键词锚点+描述长度定位；</li>
 *   <li>识别修正只追加新版本（SUPERSEDED 旧版），原转写与历史定位均不回写；</li>
 *   <li>定位结果纳入全局证据水位，视图可固定截点并按修订版本绑定。</li>
 * </ul>
 */
@Service
public class EntityLocatorService {

    public static final String TYPE_ID_CARD = "ID_CARD";
    public static final String TYPE_BANK_CARD = "BANK_CARD";
    public static final String TYPE_ADDRESS = "ADDRESS";
    public static final String TYPE_HEALTH = "HEALTH";

    /** 身份证：18 位（末位可为 X）。 */
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[0-9Xx]");
    /** 银行卡：16–19 位连续数字。 */
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");
    /** 住址锚点：住址/地址/家住 后到句末或 30 字。 */
    private static final Pattern ADDRESS = Pattern.compile("(?:住址|地址|家住)[^，。；,;]{2,30}");
    /** 健康情况锚点：诊断/患病/病史/病情/健康状况 后到句末或 30 字。 */
    private static final Pattern HEALTH = Pattern.compile("(?:诊断|患病|病史|病情|健康状况)[^，。；,;]{0,30}");

    /** 跨段缝合：前段末尾与后段开头的纯数字残片，合计长度落入卡号/证号区间。 */
    private static final Pattern TRAIL_DIGITS = Pattern.compile("\\d+$");
    private static final Pattern LEAD_DIGITS = Pattern.compile("^\\d+");

    private final SensitiveEntityRepository entityRepository;
    private final SensitiveEntityMentionRepository mentionRepository;
    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final WatermarkService watermark;
    private final ApplicationEventPublisher publisher;

    public EntityLocatorService(SensitiveEntityRepository entityRepository,
                                SensitiveEntityMentionRepository mentionRepository,
                                RecordingObjectRepository recordingRepository,
                                RecordingSegmentRepository segmentRepository,
                                WatermarkService watermark,
                                ApplicationEventPublisher publisher) {
        this.entityRepository = entityRepository;
        this.mentionRepository = mentionRepository;
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.watermark = watermark;
        this.publisher = publisher;
    }

    /** 定位结果（内存态，持久化前的中间结构）。 */
    record LocatedMention(UUID segmentId, int segmentSeq, int start, int end,
                         String coveredText, String mode) {
    }

    record LocatedEntity(String type, String canonicalValue, List<LocatedMention> mentions) {
    }

    // ---------------- 自动定位 ----------------

    /**
     * 对指定录音的当前 ACTIVE 转写自动定位并登记实体（每个逻辑实体首版 revision=1）。
     * 已有同类型同规范值的 ACTIVE 实体不重复登记（幂等）。
     */
    @Transactional
    public Map<String, Object> locate(UUID recordingId) {
        RecordingObject rec = recordingRepository.findById(recordingId)
                .orElseThrow(() -> ApiException.notFound("录音不存在: " + recordingId));
        if (!RecordingObject.STATUS_ACTIVE.equals(rec.getStatus())) {
            throw ApiException.badRequest("仅对 ACTIVE 转写定位，当前状态: " + rec.getStatus());
        }
        List<RecordingSegment> segments = segmentRepository.findByRecordingIdOrderBySeq(recordingId);
        if (segments.isEmpty()) {
            throw ApiException.badRequest("录音无片段，无法定位");
        }

        List<LocatedEntity> located = new ArrayList<>();
        // 段内模式
        for (RecordingSegment seg : segments) {
            String text = seg.getTranscript() == null ? "" : seg.getTranscript();
            for (MatchResult m : ID_CARD.matcher(text).results().toList()) {
                located.add(new LocatedEntity(TYPE_ID_CARD, m.group(),
                        List.of(new LocatedMention(seg.getId(), seg.getSeq(), m.start(), m.end(),
                                m.group(), SensitiveEntityMention.MODE_FULL))));
            }
            for (MatchResult m : BANK_CARD.matcher(text).results().toList()) {
                // 身份证 18 位数字也满足银行卡区间，已被身份证捕获的位置不重复登记
                if (overlaps(located, seg.getId(), m.start(), m.end())) {
                    continue;
                }
                located.add(new LocatedEntity(TYPE_BANK_CARD, m.group(),
                        List.of(new LocatedMention(seg.getId(), seg.getSeq(), m.start(), m.end(),
                                m.group(), SensitiveEntityMention.MODE_FULL))));
            }
            for (Pattern p : List.of(ADDRESS, HEALTH)) {
                String type = p == ADDRESS ? TYPE_ADDRESS : TYPE_HEALTH;
                for (MatchResult m : p.matcher(text).results().toList()) {
                    located.add(new LocatedEntity(type, m.group(),
                            List.of(new LocatedMention(seg.getId(), seg.getSeq(), m.start(), m.end(),
                                    m.group(), SensitiveEntityMention.MODE_FULL))));
                }
            }
        }
        // 跨段缝合：相邻片段边界的数字残片拼成卡号/证号
        stitchAcrossSegments(segments, located);

        List<Map<String, Object>> registered = new ArrayList<>();
        for (LocatedEntity le : located) {
            boolean exists = entityRepository
                    .findByRecordingIdAndStatusOrderByRegisteredSeq(recordingId, SensitiveEntity.STATUS_ACTIVE)
                    .stream().anyMatch(e -> e.getEntityType().equals(le.type())
                            && e.getCanonicalValue().equals(le.canonicalValue()));
            if (exists) {
                continue;
            }
            registered.add(persistNew(recordingId, le.type(), le.canonicalValue(), 1, null, le.mentions()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordingId", recordingId);
        out.put("locatedCount", located.size());
        out.put("registeredCount", registered.size());
        out.put("entities", registered);
        return out;
    }

    private void stitchAcrossSegments(List<RecordingSegment> segments, List<LocatedEntity> located) {
        for (int i = 0; i + 1 < segments.size(); i++) {
            RecordingSegment a = segments.get(i);
            RecordingSegment b = segments.get(i + 1);
            String ta = a.getTranscript() == null ? "" : a.getTranscript();
            String tb = b.getTranscript() == null ? "" : b.getTranscript();
            MatchResult tail = TRAIL_DIGITS.matcher(ta).results().findFirst().orElse(null);
            MatchResult head = LEAD_DIGITS.matcher(tb).results().findFirst().orElse(null);
            if (tail == null || head == null) {
                continue;
            }
            String joined = tail.group() + head.group();
            boolean isId = joined.length() == 18 && ID_CARD.matcher(joined).matches();
            boolean isCard = !isId && joined.length() >= 16 && joined.length() <= 19;
            if (!isId && !isCard) {
                continue;
            }
            // 残片本身在段内未被完整模式捕获（否则无需缝合）
            if (overlaps(located, a.getId(), tail.start(), tail.end())
                    || overlaps(located, b.getId(), head.start(), head.end())) {
                continue;
            }
            String type = isId ? TYPE_ID_CARD : TYPE_BANK_CARD;
            List<LocatedMention> parts = List.of(
                    new LocatedMention(a.getId(), a.getSeq(), tail.start(), tail.end(),
                            tail.group(), SensitiveEntityMention.MODE_PREFIX),
                    new LocatedMention(b.getId(), b.getSeq(), head.start(), head.end(),
                            head.group(), SensitiveEntityMention.MODE_SUFFIX));
            located.add(new LocatedEntity(type, joined, parts));
        }
    }

    private boolean overlaps(List<LocatedEntity> located, UUID segmentId, int start, int end) {
        return located.stream().flatMap(e -> e.mentions().stream())
                .anyMatch(m -> m.segmentId().equals(segmentId) && m.start() < end && m.end() > start);
    }

    // ---------------- 识别修正 ----------------

    public record CorrectionInput(String entityType, String previousEntityId,
                                  String correctedCanonicalValue,
                                  List<ManualMentionInput> mentions) {
    }

    /** 手工提及位置（修正时可重新指定出现位置；跨段实体给多条）。 */
    public record ManualMentionInput(UUID segmentId, int segmentSeq, int startOffset, int endOffset,
                                    String coveredText, String matchMode) {
    }

    /**
     * 识别修正：对既有实体登记新版本，旧版置 SUPERSEDED；新版本携带修正后的规范值与位置。
     * 原转写不回写，历史视图仍绑定旧修订版本。
     */
    @Transactional
    public Map<String, Object> correct(UUID recordingId, CorrectionInput in) {
        SensitiveEntity previous = entityRepository.findById(UUID.fromString(in.previousEntityId()))
                .orElseThrow(() -> ApiException.notFound("待修正实体不存在: " + in.previousEntityId()));
        if (!previous.getRecordingId().equals(recordingId)) {
            throw ApiException.badRequest("实体不属于该录音");
        }
        if (!SensitiveEntity.STATUS_ACTIVE.equals(previous.getStatus())) {
            throw ApiException.conflict("仅 ACTIVE 实体可修正，当前状态: " + previous.getStatus());
        }
        List<LocatedMention> mentions = new ArrayList<>();
        if (in.mentions() != null && !in.mentions().isEmpty()) {
            for (ManualMentionInput mm : in.mentions()) {
                mentions.add(new LocatedMention(mm.segmentId(), mm.segmentSeq(), mm.startOffset(),
                        mm.endOffset(), mm.coveredText(), mm.matchMode()));
            }
        } else {
            // 未显式给位置则沿用旧版提及位置（值修正、位置不变的常见情形）
            for (SensitiveEntityMention old : mentionRepository.findByEntityIdOrderByMentionSeq(previous.getId())) {
                mentions.add(new LocatedMention(old.getSegmentId(), old.getSegmentSeq(),
                        old.getStartOffset(), old.getEndOffset(), old.getCoveredText(), old.getMatchMode()));
            }
        }
        previous.setStatus(SensitiveEntity.STATUS_SUPERSEDED);
        entityRepository.save(previous);
        Map<String, Object> saved = persistNew(recordingId, in.entityType(), in.correctedCanonicalValue(),
                previous.getRevision() + 1, previous.getId(), mentions);
        saved.put("supersededRevision", previous.getRevision());
        // 发布被取代（旧）实体 id：既有视图的 entityPins 绑定的正是旧 id，据此准确失效
        publisher.publishEvent(new PrivacyEvents.SensitiveEntityCorrected(
                recordingId, previous.getId(), previous.getRevision() + 1));
        return saved;
    }

    private Map<String, Object> persistNew(UUID recordingId, String type, String canonical,
                                           int revision, UUID supersedesId, List<LocatedMention> mentions) {
        SensitiveEntity e = new SensitiveEntity();
        e.setId(UUID.randomUUID());
        e.setRegisteredSeq(watermark.next());
        e.setRecordingId(recordingId);
        e.setEntityType(type);
        e.setCanonicalValue(canonical);
        e.setRevision(revision);
        e.setStatus(SensitiveEntity.STATUS_ACTIVE);
        e.setSupersedesId(supersedesId);
        e.setCreatedAt(Instant.now());
        entityRepository.save(e);

        List<Map<String, Object>> mentionViews = new ArrayList<>();
        int seq = 1;
        for (LocatedMention lm : mentions) {
            SensitiveEntityMention m = new SensitiveEntityMention();
            m.setId(UUID.randomUUID());
            m.setEntityId(e.getId());
            m.setSegmentId(lm.segmentId());
            m.setSegmentSeq(lm.segmentSeq());
            m.setStartOffset(lm.start());
            m.setEndOffset(lm.end());
            m.setCoveredText(lm.coveredText());
            m.setMatchMode(lm.mode());
            m.setMentionSeq(seq++);
            m.setRegisteredSeq(e.getRegisteredSeq());
            mentionRepository.save(m);
            mentionViews.add(mentionView(m));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("entityId", e.getId().toString());
        view.put("registeredSeq", e.getRegisteredSeq());
        view.put("entityType", type);
        view.put("canonicalValue", canonical);
        view.put("revision", revision);
        view.put("supersedesId", supersedesId);
        view.put("mentions", mentionViews);
        return view;
    }

    static Map<String, Object> mentionView(SensitiveEntityMention m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("mentionId", m.getId().toString());
        v.put("segmentId", m.getSegmentId().toString());
        v.put("segmentSeq", m.getSegmentSeq());
        v.put("startOffset", m.getStartOffset());
        v.put("endOffset", m.getEndOffset());
        v.put("coveredText", m.getCoveredText());
        v.put("matchMode", m.getMatchMode());
        v.put("mentionSeq", m.getMentionSeq());
        return v;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEntities(UUID recordingId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SensitiveEntity e : entityRepository.findByRecordingIdOrderByRegisteredSeq(recordingId)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("entityId", e.getId().toString());
            v.put("entityType", e.getEntityType());
            v.put("canonicalValue", e.getCanonicalValue());
            v.put("revision", e.getRevision());
            v.put("status", e.getStatus());
            v.put("registeredSeq", e.getRegisteredSeq());
            v.put("supersedesId", e.getSupersedesId());
            v.put("mentions", mentionRepository.findByEntityIdOrderByMentionSeq(e.getId()).stream()
                    .map(EntityLocatorService::mentionView).toList());
            out.add(v);
        }
        return out;
    }
}
