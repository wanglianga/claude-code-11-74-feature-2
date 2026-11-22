package com.example.retention.privacy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证据视图端：按需即时生成、预先物化、查询与下载（失效/越权新下载被拦截并留痕）。
 */
@RestController
@RequestMapping("/api/privacy/views")
@Tag(name = "隐私-证据视图", description = "从不可变原转写按案件目的生成带规则版本的遮蔽视图；按需/物化双路线；回链位置、规则与审批")
public class EvidenceViewController {

    private final EvidenceViewService viewService;

    public EvidenceViewController(EvidenceViewService viewService) {
        this.viewService = viewService;
    }

    @PostMapping("/on-demand")
    @Operation(summary = "按需生成：调阅时从不可变原转写即时生成带规则版本的遮蔽视图（有生成等待，不留长期副本）")
    public Map<String, Object> onDemand(@RequestBody EvidenceViewService.ViewRequest req) {
        return viewService.generateOnDemand(req);
    }

    @PostMapping("/materialized")
    @Operation(summary = "预物化：为常用角色生成/复用 FRESH 视图；规则、转写、识别或范围变化后准确失效")
    public Map<String, Object> materialized(@RequestBody EvidenceViewService.ViewRequest req) {
        return viewService.materialize(req);
    }

    @GetMapping
    @Operation(summary = "证据视图清单（含 FRESH/STALE 状态与失效原因）")
    public List<EvidenceView> list() {
        return viewService.listViews();
    }

    @GetMapping("/{id}")
    @Operation(summary = "证据视图详情（含逐处遮蔽回链）")
    public EvidenceView get(@PathVariable UUID id) {
        return viewService.requireView(id);
    }

    @PostMapping("/{id}/download")
    @Operation(summary = "下载视图：过当前授权与有效性核验；失效/范围收窄/审批失效的新下载被拦截（403）并留痕")
    public Map<String, Object> download(@PathVariable UUID id,
                                        @RequestBody EvidenceViewService.DownloadRequest req) {
        return viewService.download(id, req);
    }

    @GetMapping("/{id}/downloads")
    @Operation(summary = "该视图的下载留痕（放行与拦截）")
    public List<EvidenceViewDownload> downloads(@PathVariable UUID id) {
        return viewService.downloads(id);
    }

    @GetMapping("/downloads/all")
    @Operation(summary = "全部视图下载留痕")
    public List<EvidenceViewDownload> allDownloads() {
        return viewService.allDownloads();
    }
}
