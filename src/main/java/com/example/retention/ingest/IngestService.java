package com.example.retention.ingest;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.example.retention.disposal.DisposalService;
import com.example.retention.graph.ChangeImpact;
import com.example.retention.graph.FactView;
import com.example.retention.graph.WorldFolder;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.obligations.LedgerMaintenanceLog;
import com.example.retention.obligations.ObligationLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接入服务：录音对象与业务事件的接入账。
 * 相同来源版本与相同内容重复到达 → 幂等重放，不重复建立义务；
 * 相同版本内容不同 → 保留冲突待核；新版本 → 修订链 supersede。
 */
@Service
public class IngestService {

    private static final Set<String> EVENT_TYPES = Set.of(
            "PRODUCT_TERMINATED", "COLLECTION_CLOSED", "COMPLAINT_FILED", "CASE_OPENED",
            "FACT_CORRECTED", "FACT_CONFIRMED", "FACT_REVOKED",
            "LINK_ASSERTED", "LINK_REVOKED", "HOLD_PLACED", "HOLD_RELEASED");

    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final FieldClassificationRepository fieldRepository;
    private final BusinessEventRepository eventRepository;
    private final IngestConflictRepository conflictRepository;
    private final WatermarkService watermark;
    private final WorldService worldService;
    private final ObligationLedgerService ledgerService;
    private final DisposalService disposalService;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper canonicalMapper;

    public IngestService(RecordingObjectRepository recordingRepository,
                         RecordingSegmentRepository segmentRepository,
                         FieldClassificationRepository fieldRepository,
                         BusinessEventRepository eventRepository,
                         IngestConflictRepository conflictRepository,
                         WatermarkService watermark,
                         WorldService worldService,
                         ObligationLedgerService ledgerService,
                         DisposalService disposalService,
                         ApplicationEventPublisher publisher) {
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.fieldRepository = fieldRepository;
        this.eventRepository = eventRepository;
        this.conflictRepository = conflictRepository;
        this.watermark = watermark;
        this.worldService = worldService;
        this.ledgerService = ledgerService;
        this.disposalService = disposalService;
        this.publisher = publisher;
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    // ---------------- 录音对象接入 ----------------

    public record SegmentInput(int seq, long startMs, long endMs, String transcript,
                               String contentHash, List<FieldInput> fields) {
    }

    public record FieldInput(String fieldKey, String classification) {
    }

    public record RecordingInput(String sourceSystem, String sourceObjectId, int sourceVersion,
                                 String idempotencyKey, String contentHash, int channelCount,
                                 long durationMs, Instant callBusinessTime, Instant platformReceivedTime,
                                 List<SegmentInput> segments) {
    }

    public record RecordingOutcome(String outcome, UUID recordingId, List<UUID> segmentIds,
                                   UUID conflictId, String message) {
    }

    @Transactional
    public RecordingOutcome ingestRecording(RecordingInput in) {
        validate(in);
        String incomingHash = in.contentHash();

        var byKey = recordingRepository.findByIdempotencyKey(in.idempotencyKey());
        if (byKey.isPresent()) {
            RecordingObject existing = byKey.get();
            if (existing.getContentHash().equals(incomingHash)) {
                return new RecordingOutcome("DUPLICATE_IDEMPOTENT", existing.getId(),
                        segmentIds(existing.getId()), null, "相同幂等键与内容，幂等重放不重复建账");
            }
            IngestConflict c = saveConflict("RECORDING", in.sourceObjectId(), in.sourceVersion(),
                    existing.getContentHash(), incomingHash, Map.of("request", summarize(in)));
            return new RecordingOutcome("CONFLICT_PENDING", existing.getId(),
                    segmentIds(existing.getId()), c.getId(), "相同幂等键但内容不同，保留冲突待核");
        }

        List<RecordingObject> versions = recordingRepository
                .findBySourceObjectIdOrderBySourceVersionDesc(in.sourceObjectId());
        UUID supersedesId = null;
        if (!versions.isEmpty()) {
            RecordingObject latest = versions.get(0);
            if (in.sourceVersion() < latest.getSourceVersion()) {
                throw ApiException.badRequest("来源版本回退: 已存在 v" + latest.getSourceVersion()
                        + "，拒绝接入 v" + in.sourceVersion());
            }
            if (in.sourceVersion() == latest.getSourceVersion()) {
                if (latest.getContentHash().equals(incomingHash)) {
                    return new RecordingOutcome("DUPLICATE_IDEMPOTENT", latest.getId(),
                            segmentIds(latest.getId()), null, "相同来源版本与内容重复到达，不重复建立义务");
                }
                IngestConflict c = saveConflict("RECORDING", in.sourceObjectId(), in.sourceVersion(),
                        latest.getContentHash(), incomingHash, Map.of("request", summarize(in)));
                return new RecordingOutcome("CONFLICT_PENDING", latest.getId(),
                        segmentIds(latest.getId()), c.getId(), "相同来源版本内容不同，保留冲突待核");
            }
            supersedesId = latest.getId();
            latest.setStatus(RecordingObject.STATUS_SUPERSEDED);
            recordingRepository.save(latest);
        }

        RecordingObject rec = new RecordingObject();
        rec.setId(UUID.randomUUID());
        rec.setSeq(watermark.next());
        rec.setSourceSystem(in.sourceSystem());
        rec.setSourceObjectId(in.sourceObjectId());
        rec.setSourceVersion(in.sourceVersion());
        rec.setContentHash(in.contentHash());
        rec.setChannelCount(in.channelCount());
        rec.setDurationMs(in.durationMs());
        rec.setCallBusinessTime(in.callBusinessTime());
        rec.setPlatformReceivedTime(in.platformReceivedTime());
        rec.setIdempotencyKey(in.idempotencyKey());
        rec.setStatus(RecordingObject.STATUS_ACTIVE);
        rec.setSupersedesId(supersedesId);
        rec.setCreatedAt(Instant.now());
        recordingRepository.save(rec);

        for (SegmentInput s : in.segments()) {
            RecordingSegment seg = new RecordingSegment();
            seg.setId(UUID.randomUUID());
            seg.setRecordingId(rec.getId());
            seg.setSeq(s.seq());
            seg.setStartMs(s.startMs());
            seg.setEndMs(s.endMs());
            seg.setTranscript(s.transcript());
            seg.setContentHash(s.contentHash() == null ? "sha256:" + s.seq() : s.contentHash());
            segmentRepository.save(seg);
            if (s.fields() != null) {
                for (FieldInput f : s.fields()) {
                    FieldClassification fc = new FieldClassification();
                    fc.setId(UUID.randomUUID());
                    fc.setSegmentId(seg.getId());
                    fc.setFieldKey(f.fieldKey());
                    fc.setClassification(f.classification());
                    fieldRepository.save(fc);
                }
            }
        }
        // 新版本取代旧转写：发布更正事件使隐私物化视图失效（旧原件仅置 SUPERSEDED，不删除）
        if (supersedesId != null) {
            publisher.publishEvent(new com.example.retention.privacy.PrivacyEvents.RecordingSuperseded(
                    supersedesId, rec.getId(), rec.getSeq()));
        }
        return new RecordingOutcome(supersedesId == null ? "CREATED" : "CREATED_SUPERSEDES",
                rec.getId(), segmentIds(rec.getId()), null,
                supersedesId == null ? "已接入" : "新版本接入，已取代 v" + (in.sourceVersion() - 1));
    }

    private List<UUID> segmentIds(UUID recordingId) {
        return segmentRepository.findByRecordingIdOrderBySeq(recordingId).stream()
                .map(RecordingSegment::getId).toList();
    }

    private void validate(RecordingInput in) {
        if (in.segments() == null || in.segments().isEmpty()) {
            throw ApiException.badRequest("录音必须至少包含一个片段");
        }
        long covered = 0;
        for (SegmentInput s : in.segments()) {
            if (s.startMs() < 0 || s.endMs() <= s.startMs()) {
                throw ApiException.badRequest("片段边界不合法: seq=" + s.seq());
            }
            covered += s.endMs() - s.startMs();
        }
        if (covered > in.durationMs()) {
            throw ApiException.badRequest("片段边界总时长超过录音时长");
        }
    }

    private Map<String, Object> summarize(RecordingInput in) {
        return Map.of("sourceObjectId", in.sourceObjectId(), "sourceVersion", in.sourceVersion(),
                "contentHash", in.contentHash(), "idempotencyKey", in.idempotencyKey());
    }

    // ---------------- 业务事件接入 ----------------

    public record EventInput(String sourceSystem, String sourceEventId, int eventVersion,
                             String eventType, String idempotencyKey,
                             Instant businessEffectiveTime, Instant platformReceivedTime,
                             Map<String, Object> payload) {
    }

    public record EventOutcome(String outcome, UUID eventId, Long seq, UUID conflictId,
                             Integer affectedSegments, Integer compensationsWritten,
                             String message) {
    }

    @Transactional
    public EventOutcome ingestEvent(EventInput in) {
        if (!EVENT_TYPES.contains(in.eventType())) {
            throw ApiException.badRequest("未知事件类型: " + in.eventType());
        }
        validatePayload(in);
        String hash = hashPayload(in.payload());

        var byKey = eventRepository.findByIdempotencyKey(in.idempotencyKey());
        if (byKey.isPresent()) {
            BusinessEvent existing = byKey.get();
            if (existing.getPayloadHash().equals(hash)) {
                return new EventOutcome("DUPLICATE_IDEMPOTENT", existing.getId(), existing.getSeq(),
                        null, 0, 0, "相同幂等键与内容，幂等重放不重复应用");
            }
            IngestConflict c = saveConflict("EVENT", in.sourceEventId(), in.eventVersion(),
                    existing.getPayloadHash(), hash, in.payload());
            return new EventOutcome("CONFLICT_PENDING", existing.getId(), existing.getSeq(),
                    c.getId(), 0, 0, "相同幂等键但内容不同，保留冲突待核");
        }

        List<BusinessEvent> versions = eventRepository
                .findBySourceEventIdOrderByEventVersionDesc(in.sourceEventId());
        UUID supersedesId = null;
        if (!versions.isEmpty()) {
            BusinessEvent latest = versions.get(0);
            if (in.eventVersion() < latest.getEventVersion()) {
                throw ApiException.badRequest("事件版本回退: 已存在 v" + latest.getEventVersion());
            }
            if (in.eventVersion() == latest.getEventVersion()) {
                if (latest.getPayloadHash().equals(hash)) {
                    return new EventOutcome("DUPLICATE_IDEMPOTENT", latest.getId(), latest.getSeq(),
                            null, 0, 0, "相同来源版本与内容重复到达，不重复建立义务");
                }
                IngestConflict c = saveConflict("EVENT", in.sourceEventId(), in.eventVersion(),
                        latest.getPayloadHash(), hash, in.payload());
                return new EventOutcome("CONFLICT_PENDING", latest.getId(), latest.getSeq(),
                        c.getId(), 0, 0, "相同来源版本内容不同，保留冲突待核");
            }
            supersedesId = latest.getId();
        }

        BusinessEvent e = new BusinessEvent();
        e.setId(UUID.randomUUID());
        e.setSeq(watermark.next());
        e.setSourceSystem(in.sourceSystem());
        e.setSourceEventId(in.sourceEventId());
        e.setEventVersion(in.eventVersion());
        e.setEventType(in.eventType());
        e.setBusinessEffectiveTime(in.businessEffectiveTime());
        e.setPlatformReceivedTime(in.platformReceivedTime());
        e.setPayload(in.payload());
        e.setPayloadHash(hash);
        e.setIdempotencyKey(in.idempotencyKey());
        e.setStatus(BusinessEvent.STATUS_APPLIED);
        e.setSupersedesId(supersedesId);
        e.setCreatedAt(Instant.now());
        eventRepository.save(e);

        // 变更传播：义务账补偿 + 候选撤销/升级
        ChangeImpact impact = computeImpact(e);
        LedgerMaintenanceLog log = ledgerService.maintain(impact, "event:" + e.getSeq());
        disposalService.onWorldChange(impact);

        return new EventOutcome("APPLIED", e.getId(), e.getSeq(), null,
                impact.segmentIds().size(), log.getEntriesAdded() + log.getEntriesVoided(),
                "事件已应用");
    }

    private void validatePayload(EventInput in) {
        Map<String, Object> p = in.payload();
        if (p == null) {
            throw ApiException.badRequest("payload 不能为空");
        }
        try {
            switch (in.eventType()) {
                case "PRODUCT_TERMINATED", "COLLECTION_CLOSED", "COMPLAINT_FILED", "CASE_OPENED",
                     "FACT_CORRECTED", "FACT_CONFIRMED", "FACT_REVOKED" ->
                        WorldFolder.str(p, "fact_key");
                case "LINK_ASSERTED", "LINK_REVOKED" -> {
                    UUID.fromString(WorldFolder.str(p, "segment_id"));
                    WorldFolder.str(p, "fact_key");
                }
                case "HOLD_PLACED" -> {
                    WorldFolder.str(p, "hold_key");
                    String scope = WorldFolder.str(p, "scope_type");
                    if (!Set.of("SEGMENT", "RECORDING", "FACT", "CASE").contains(scope)) {
                        throw ApiException.badRequest("未知保全作用域: " + scope);
                    }
                    WorldFolder.str(p, "scope_ref");
                }
                case "HOLD_RELEASED" -> WorldFolder.str(p, "hold_key");
                default -> {
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("payload 校验失败: " + ex.getMessage());
        }
    }

    /** 计算事件影响面：受影响的片段与整对象。 */
    private ChangeImpact computeImpact(BusinessEvent e) {
        WorldState world = worldService.currentWorld();
        Map<String, Object> p = e.getPayload();
        ChangeImpact impact = ChangeImpact.of("event:" + e.getEventType() + "#" + e.getSeq());
        switch (e.getEventType()) {
            case "PRODUCT_TERMINATED", "COLLECTION_CLOSED", "COMPLAINT_FILED", "CASE_OPENED",
                 "FACT_CORRECTED", "FACT_CONFIRMED", "FACT_REVOKED" -> {
                String factKey = WorldFolder.str(p, "fact_key");
                addSegmentsLinkedToFact(impact, world, factKey);
            }
            case "LINK_ASSERTED", "LINK_REVOKED" -> {
                UUID segmentId = UUID.fromString(WorldFolder.str(p, "segment_id"));
                segmentRepository.findById(segmentId).ifPresent(seg ->
                        impact.withSegment(seg.getId(), seg.getRecordingId()));
            }
            case "HOLD_PLACED", "HOLD_RELEASED" -> {
                String scopeType = WorldFolder.str(p, "scope_type");
                String scopeRef = WorldFolder.str(p, "scope_ref");
                switch (scopeType) {
                    case "SEGMENT" -> segmentRepository.findById(UUID.fromString(scopeRef))
                            .ifPresent(seg -> impact.withSegment(seg.getId(), seg.getRecordingId()));
                    case "RECORDING" -> impact.withRecording(UUID.fromString(scopeRef));
                    case "FACT" -> addSegmentsLinkedToFact(impact, world, scopeRef);
                    case "CASE" -> {
                        for (FactView f : world.facts().values()) {
                            if (scopeRef.equals(f.caseId())) {
                                addSegmentsLinkedToFact(impact, world, f.factKey());
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
        return impact;
    }

    private void addSegmentsLinkedToFact(ChangeImpact impact, WorldState world, String factKey) {
        world.links().values().stream()
                .filter(l -> l.active() && l.factKey().equals(factKey))
                .forEach(l -> segmentRepository.findById(l.segmentId()).ifPresent(seg ->
                        impact.withSegment(seg.getId(), seg.getRecordingId())));
    }

    private IngestConflict saveConflict(String entityType, String naturalKey, int version,
                                        String existingHash, String incomingHash,
                                        Map<String, Object> payload) {
        IngestConflict c = new IngestConflict();
        c.setId(UUID.randomUUID());
        c.setEntityType(entityType);
        c.setNaturalKey(naturalKey);
        c.setSourceVersion(version);
        c.setExistingHash(existingHash);
        c.setIncomingHash(incomingHash);
        c.setIncomingPayload(payload);
        c.setStatus("OPEN");
        c.setCreatedAt(Instant.now());
        return conflictRepository.save(c);
    }

    private String hashPayload(Map<String, Object> payload) {
        try {
            String canonical = canonicalMapper.writeValueAsString(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("无法计算内容哈希", ex);
        }
    }
}
