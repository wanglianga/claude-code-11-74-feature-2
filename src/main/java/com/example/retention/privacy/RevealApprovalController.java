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

/** 临时还原审批台端：申请、审批/驳回、撤销与查询。 */
@RestController
@RequestMapping("/api/privacy/reveal-approvals")
@Tag(name = "隐私-临时还原审批台", description = "对已遮蔽实体的限时去遮蔽申请-审批；最小实体授权、有效期、撤销与留痕")
public class RevealApprovalController {

    private final RevealApprovalService approvalService;

    public RevealApprovalController(RevealApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    public record DecisionRequest(boolean approve, String approver, String note) {
    }

    public record RevokeRequest(String note) {
    }

    @PostMapping
    @Operation(summary = "提交临时还原申请（指定最小实体集合与有效期）")
    public RevealApproval apply(@RequestBody RevealApprovalService.ApplyInput in) {
        return approvalService.apply(in);
    }

    @PostMapping("/{approvalNo}/decision")
    @Operation(summary = "审批决定：批准或驳回")
    public RevealApproval decide(@PathVariable String approvalNo,
                                 @RequestBody DecisionRequest req) {
        return approvalService.decide(approvalNo, req.approve(), req.approver(), req.note());
    }

    @PostMapping("/{approvalNo}/revoke")
    @Operation(summary = "撤销已批准审批，并使引用它的 FRESH 物化视图失效")
    public RevealApproval revoke(@PathVariable String approvalNo, @RequestBody RevokeRequest req) {
        return approvalService.revoke(approvalNo, req.note());
    }

    @GetMapping
    @Operation(summary = "还原审批清单")
    public List<RevealApproval> list() {
        return approvalService.list();
    }

    @GetMapping("/recordings/{recordingId}")
    @Operation(summary = "某录音的还原审批")
    public List<RevealApproval> forRecording(@PathVariable UUID recordingId) {
        return approvalService.listForRecording(recordingId);
    }

    @GetMapping("/nos")
    @Operation(summary = "审批单号索引（便于联调）")
    public Map<String, Object> numbers() {
        return Map.of("approvals", approvalService.list().stream()
                .map(a -> Map.of("approvalNo", a.getApprovalNo(), "status", a.getStatus())).toList());
    }
}
