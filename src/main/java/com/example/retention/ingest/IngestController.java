package com.example.retention.ingest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 接入端：模拟录音对象、结构化业务事件与接入账查询。 */
@RestController
@RequestMapping("/api/ingest")
@Tag(name = "接入账", description = "录音对象与业务事件接入：幂等、修订链、冲突待核")
public class IngestController {

    private final IngestService ingestService;
    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final FieldClassificationRepository fieldRepository;
    private final BusinessEventRepository eventRepository;
    private final IngestConflictRepository conflictRepository;

    public IngestController(IngestService ingestService,
                            RecordingObjectRepository recordingRepository,
                            RecordingSegmentRepository segmentRepository,
                            FieldClassificationRepository fieldRepository,
                            BusinessEventRepository eventRepository,
                            IngestConflictRepository conflictRepository) {
        this.ingestService = ingestService;
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.fieldRepository = fieldRepository;
        this.eventRepository = eventRepository;
        this.conflictRepository = conflictRepository;
    }

    @PostMapping("/recordings")
    @Operation(summary = "接入模拟录音对象（含片段边界、声道、时长、两类时间、哈希、幂等键）")
    public ResponseEntity<IngestService.RecordingOutcome> ingestRecording(
            @RequestBody IngestService.RecordingInput input) {
        IngestService.RecordingOutcome outcome = ingestService.ingestRecording(input);
        HttpStatus status = "CONFLICT_PENDING".equals(outcome.outcome())
                ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome);
    }

    @PostMapping("/events")
    @Operation(summary = "接入结构化业务事件（业务生效时间+平台接收时间），触发义务传播与候选撤销")
    public ResponseEntity<IngestService.EventOutcome> ingestEvent(
            @RequestBody IngestService.EventInput input) {
        IngestService.EventOutcome outcome = ingestService.ingestEvent(input);
        HttpStatus status = "CONFLICT_PENDING".equals(outcome.outcome())
                ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome);
    }

    @GetMapping("/recordings")
    @Operation(summary = "接入账：录音对象清单")
    public List<RecordingObject> recordings() {
        return recordingRepository.findAll();
    }

    @GetMapping("/recordings/{id}")
    @Operation(summary = "录音对象详情（含片段与已分类字段）")
    public Object recording(@PathVariable UUID id) {
        RecordingObject rec = recordingRepository.findById(id)
                .orElseThrow(() -> com.example.retention.common.ApiException.notFound("录音不存在"));
        List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(id);
        return java.util.Map.of("recording", rec, "segments", segs,
                "fields", fieldRepository.findBySegmentIdIn(segs.stream()
                        .map(RecordingSegment::getId).toList()));
    }

    @GetMapping("/events")
    @Operation(summary = "接入账：业务事件清单（按水位序号）")
    public List<BusinessEvent> events() {
        return eventRepository.findAllByOrderBySeq();
    }

    @GetMapping("/conflicts")
    @Operation(summary = "冲突待核清单")
    public List<IngestConflict> conflicts() {
        return conflictRepository.findAll();
    }
}
