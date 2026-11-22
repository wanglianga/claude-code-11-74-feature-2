package com.example.retention.graph;

import com.example.retention.common.ApiException;
import com.example.retention.ingest.FieldClassificationRepository;
import com.example.retention.ingest.RecordingObject;
import com.example.retention.ingest.RecordingObjectRepository;
import com.example.retention.ingest.RecordingSegment;
import com.example.retention.ingest.RecordingSegmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证据关系图：连接文件、片段、已分类字段与业务事实。
 * 边只来自显式关联事件，不凭账户相同把全部通话归入同一案件。
 */
@RestController
@RequestMapping("/api/graph")
@Tag(name = "证据关系图", description = "文件-片段-字段-事实的显式关系查询")
public class GraphController {

    private final RecordingObjectRepository recordingRepository;
    private final RecordingSegmentRepository segmentRepository;
    private final FieldClassificationRepository fieldRepository;
    private final WorldService worldService;

    public GraphController(RecordingObjectRepository recordingRepository,
                           RecordingSegmentRepository segmentRepository,
                           FieldClassificationRepository fieldRepository,
                           WorldService worldService) {
        this.recordingRepository = recordingRepository;
        this.segmentRepository = segmentRepository;
        this.fieldRepository = fieldRepository;
        this.worldService = worldService;
    }

    @GetMapping("/recordings/{id}")
    @Operation(summary = "整对象关系图：录音→片段→字段，及片段→事实的显式边")
    public Map<String, Object> recordingGraph(@PathVariable UUID id) {
        RecordingObject rec = recordingRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("录音不存在: " + id));
        WorldState world = worldService.currentWorld();
        List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySeq(id);
        List<Map<String, Object>> segNodes = segs.stream().map(seg -> {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("segment", seg);
            n.put("fields", fieldRepository.findBySegmentId(seg.getId()));
            n.put("factLinks", world.activeLinksOf(seg.getId()).stream().map(l -> {
                FactView f = world.fact(l.factKey());
                return Map.of("factKey", l.factKey(),
                        "factType", f != null ? f.factType() : "UNKNOWN",
                        "factStatus", f != null ? f.status() : "MISSING",
                        "caseId", f != null && f.caseId() != null ? f.caseId() : "");
            }).toList());
            return n;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recording", rec);
        out.put("segments", segNodes);
        out.put("watermark", world.watermark());
        return out;
    }

    @GetMapping("/segments/{id}")
    @Operation(summary = "片段关系图：字段、事实边、覆盖保全")
    public Map<String, Object> segmentGraph(@PathVariable UUID id) {
        RecordingSegment seg = segmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("片段不存在: " + id));
        WorldState world = worldService.currentWorld();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segment", seg);
        out.put("fields", fieldRepository.findBySegmentId(id));
        out.put("factLinks", world.activeLinksOf(id).stream()
                .map(l -> Map.of("factKey", l.factKey(), "revision", l.revision())).toList());
        out.put("facts", world.activeLinksOf(id).stream()
                .map(l -> world.fact(l.factKey())).filter(java.util.Objects::nonNull).toList());
        out.put("watermark", world.watermark());
        return out;
    }

    @GetMapping("/facts")
    @Operation(summary = "当前全部业务事实")
    public Map<String, Object> facts() {
        WorldState world = worldService.currentWorld();
        return Map.of("watermark", world.watermark(), "facts", world.facts().values());
    }

    @GetMapping("/holds")
    @Operation(summary = "当前全部保全")
    public Map<String, Object> holds() {
        WorldState world = worldService.currentWorld();
        return Map.of("watermark", world.watermark(), "holds", world.allHolds());
    }
}
