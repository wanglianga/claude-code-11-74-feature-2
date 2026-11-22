package com.example.retention.disposal;

import com.example.retention.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 处置编排端：计划、候选、模拟执行。 */
@RestController
@RequestMapping("/api/disposal")
@Tag(name = "处置编排", description = "候选清单、依赖版本、阻断原因；提交前重新核验；模拟执行")
public class DisposalController {

    private final DisposalService disposalService;
    private final DisposalPlanRepository planRepository;
    private final DisposalCandidateRepository candidateRepository;

    public DisposalController(DisposalService disposalService,
                              DisposalPlanRepository planRepository,
                              DisposalCandidateRepository candidateRepository) {
        this.disposalService = disposalService;
        this.planRepository = planRepository;
        this.candidateRepository = candidateRepository;
    }

    public record PlanRequest(LocalDate asOf, String route) {
    }

    @PostMapping("/plans")
    @Operation(summary = "生成处置计划：整对象候选清单 + 阻断原因 + 依赖版本（水位/规则/路线）")
    public Map<String, Object> createPlan(@RequestBody PlanRequest req) {
        if (req.asOf() == null) {
            throw ApiException.badRequest("asOf 不能为空");
        }
        return disposalService.createPlan(req.asOf(), req.route() == null ? "A" : req.route());
    }

    @GetMapping("/plans")
    @Operation(summary = "处置计划清单")
    public List<DisposalPlan> plans() {
        return planRepository.findAll();
    }

    @GetMapping("/plans/{id}")
    @Operation(summary = "计划详情（含候选与阻断项）")
    public Map<String, Object> plan(@PathVariable UUID id) {
        DisposalPlan plan = planRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("计划不存在: " + id));
        List<Map<String, Object>> items = candidateRepository.findByPlanId(id).stream()
                .map(DisposalService::candidateView).toList();
        return Map.of("plan", plan, "items", items);
    }

    @GetMapping("/candidates")
    @Operation(summary = "候选清单（可按状态过滤）")
    public List<Map<String, Object>> candidates(@RequestParam(required = false) String status) {
        List<DisposalCandidate> list = status == null
                ? candidateRepository.findAll() : candidateRepository.findByStatus(status);
        return list.stream().map(DisposalService::candidateView).toList();
    }

    @GetMapping("/candidates/{id}")
    @Operation(summary = "候选详情")
    public Map<String, Object> candidate(@PathVariable UUID id) {
        return candidateRepository.findById(id)
                .map(DisposalService::candidateView)
                .orElseThrow(() -> ApiException.notFound("候选不存在: " + id));
    }

    public record ExecuteRequest(String simulate) {
    }

    @PostMapping("/candidates/{id}/execute")
    @Operation(summary = "模拟执行：提交前重新核验义务与保全；simulate=CONFIRM/REJECT/TIMEOUT")
    public Map<String, Object> execute(@PathVariable UUID id,
                                       @RequestBody(required = false) ExecuteRequest req) {
        return disposalService.execute(id, req == null ? "CONFIRM" : req.simulate());
    }
}
