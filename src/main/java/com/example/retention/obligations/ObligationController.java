package com.example.retention.obligations;

import com.example.retention.common.ApiException;
import com.example.retention.graph.WorldService;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingObject;
import com.example.retention.ingest.RecordingObjectRepository;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import com.example.retention.rules.RetentionRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 义务解释端：对每个片段列出全部有效义务及其来源；
 * 路线A（截点遍历派生）与路线B（不可变义务账+补偿记录）在同一截点结论一致。
 */
@RestController
@RequestMapping("/api/obligations")
@Tag(name = "义务解释", description = "片段/整对象义务求值（双路线）与一致性对比")
public class ObligationController {

    private final ObligationCore core;
    private final ObligationLedgerService ledgerService;
    private final WorldService worldService;
    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final ObligationEntryRepository entryRepository;
    private final LedgerMaintenanceLogRepository logRepository;

    public ObligationController(ObligationCore core,
                                ObligationLedgerService ledgerService,
                                WorldService worldService,
                                RecordingObjectRepository recordingRepository,
                                RecordingSegmentRepository segmentRepository,
                                ObligationEntryRepository entryRepository,
                                LedgerMaintenanceLogRepository logRepository) {
        this.core = core;
        this.ledgerService = ledgerService;
        this.worldService = worldService;
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.entryRepository = entryRepository;
        this.logRepository = logRepository;
    }

    @GetMapping("/segments/{segmentId}")
    @Operation(summary = "片段义务求值：全部有效义务及来源、保全、处置资格")
    public Map<String, Object> segmentEvaluation(
            @PathVariable UUID segmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "A") String route) {
        RecordingSegment seg = segmentRepository.findById(segmentId)
                .orElseThrow(() -> ApiException.notFound("片段不存在: " + segmentId));
        WorldState world = worldService.currentWorld();
        EvalStats stats = new EvalStats();
        stats.eventsFolded = world.eventsFolded();
        SegmentEvaluation eval = "B".equals(route)
                ? ledgerService.evaluateSegment(seg, world, asOf, stats)
                : core.evaluateSegment(seg, world, worldService.allRules(), asOf, stats);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("asOf", asOf);
        out.put("watermark", world.watermark());
        out.put("evaluation", eval);
        out.put("evalCost", stats.toMap());
        return out;
    }

    @GetMapping("/recordings/{recordingId}")
    @Operation(summary = "整对象义务求值：全部片段均具备处置资格时整对象才是候选")
    public Map<String, Object> recordingEvaluation(
            @PathVariable UUID recordingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "A") String route) {
        RecordingObject rec = recordingRepository.findById(recordingId)
                .orElseThrow(() -> ApiException.notFound("录音不存在: " + recordingId));
        List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(recordingId);
        WorldState world = worldService.currentWorld();
        EvalStats stats = new EvalStats();
        stats.eventsFolded = world.eventsFolded();
        RecordingEvaluation eval = "B".equals(route)
                ? ledgerService.evaluateRecording(recordingId, segs, world, asOf, stats)
                : core.evaluateRecording(recordingId, segs, world, worldService.allRules(), asOf, stats);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("asOf", asOf);
        out.put("watermark", world.watermark());
        out.put("evaluation", eval);
        out.put("evalCost", stats.toMap());
        return out;
    }

    @GetMapping("/compare")
    @Operation(summary = "双路线一致性对比：同一截点相同保留结论与处置资格；输出各自代价")
    public Map<String, Object> compare(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        WorldState world = worldService.currentWorld();
        List<RetentionRule> rules = worldService.allRules();
        EvalStats statsA = new EvalStats();
        EvalStats statsB = new EvalStats();
        statsA.eventsFolded = world.eventsFolded();
        statsB.eventsFolded = world.eventsFolded();
        List<Map<String, Object>> recordings = new ArrayList<>();
        boolean allEqual = true;
        for (RecordingObject rec : recordingRepository
                .findByStatus(RecordingObject.STATUS_ACTIVE)) {
            List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(rec.getId());
            RecordingEvaluation a = core.evaluateRecording(rec.getId(), segs, world, rules, asOf, statsA);
            RecordingEvaluation b = ledgerService.evaluateRecording(rec.getId(), segs, world, asOf, statsB);
            boolean equal = a.eligible() == b.eligible()
                    && obligationIdentities(a).equals(obligationIdentities(b));
            allEqual = allEqual && equal;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordingId", rec.getId());
            row.put("sourceObjectId", rec.getSourceObjectId());
            row.put("equal", equal);
            row.put("routeA", Map.of("eligible", a.eligible(), "blockReasons", a.blockReasons(),
                    "obligations", obligationIdentities(a)));
            row.put("routeB", Map.of("eligible", b.eligible(), "blockReasons", b.blockReasons(),
                    "obligations", obligationIdentities(b)));
            recordings.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf);
        out.put("watermark", world.watermark());
        out.put("allEqual", allEqual);
        out.put("recordings", recordings);
        out.put("costRouteA", statsA.toMap());
        out.put("costRouteB", statsB.toMap());
        return out;
    }

    private List<String> obligationIdentities(RecordingEvaluation eval) {
        return eval.segments().stream()
                .flatMap(s -> s.obligations().stream()
                        .map(o -> "seg#" + s.segmentSeq() + ":" + o.identity() + ":" + o.state()))
                .sorted()
                .toList();
    }

    @GetMapping("/ledger")
    @Operation(summary = "路线B 义务账原始条目（含 VOID/RECTIFY 补偿记录）")
    public Object ledger(@RequestParam(required = false) UUID segmentId) {
        if (segmentId != null) {
            return entryRepository.findBySegmentIdOrderByEntrySeq(segmentId);
        }
        return entryRepository.findAll();
    }

    @GetMapping("/maintenance-log")
    @Operation(summary = "义务账维护日志：每次更正的补偿写入量（与路线A遍历范围对比代价）")
    public List<LedgerMaintenanceLog> maintenanceLog() {
        return logRepository.findAll();
    }
}
