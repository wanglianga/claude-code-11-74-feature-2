package com.example.retention.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 调阅端：求值并固定快照、历史复演、再次下载（过当前授权）。 */
@RestController
@RequestMapping("/api/access")
@Tag(name = "调阅", description = "按角色/案件范围/用途投影；快照固定水位与规则版本；复演与下载")
public class AccessController {

    private final AccessService accessService;
    private final AccessSnapshotRepository snapshotRepository;

    public AccessController(AccessService accessService,
                            AccessSnapshotRepository snapshotRepository) {
        this.accessService = accessService;
        this.snapshotRepository = snapshotRepository;
    }

    @PostMapping("/evaluations")
    @Operation(summary = "调阅求值：返回允许片段、字段及拒绝理由，并固定证据水位与规则版本")
    public Map<String, Object> evaluate(@RequestBody AccessService.AccessRequest req) {
        return accessService.evaluateAndSnapshot(req);
    }

    @GetMapping("/snapshots")
    @Operation(summary = "调阅快照清单")
    public List<AccessSnapshot> snapshots() {
        return snapshotRepository.findAll();
    }

    @GetMapping("/snapshots/{id}")
    @Operation(summary = "调阅快照详情（含当时判断结果）")
    public AccessSnapshot snapshot(@PathVariable UUID id) {
        return snapshotRepository.findById(id)
                .orElseThrow(() -> com.example.retention.common.ApiException.notFound("快照不存在"));
    }

    @PostMapping("/snapshots/{id}/replay")
    @Operation(summary = "历史复演：按快照水位与固定规则版本重算当时判断")
    public Map<String, Object> replay(@PathVariable UUID id) {
        return accessService.replay(id);
    }

    @PostMapping("/snapshots/{id}/download")
    @Operation(summary = "再次下载材料：须通过当前授权，只放行快照与当前授权的交集")
    public ResponseEntity<Map<String, Object>> download(@PathVariable UUID id,
                                                        @RequestBody AccessService.DownloadRequest req) {
        AccessService.DownloadResult result = accessService.download(id, req);
        return result.granted()
                ? ResponseEntity.ok(result.body())
                : ResponseEntity.status(HttpStatus.FORBIDDEN).body(result.body());
    }
}
