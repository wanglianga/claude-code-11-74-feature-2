package com.example.retention.privacy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 遮蔽规则版本库端：登记不可变规则版本（修订史）与查询。 */
@RestController
@RequestMapping("/api/privacy/masking-rules")
@Tag(name = "隐私-遮蔽规则版本库", description = "按 ruleCode+version 不可变版本化；修订后旧版仍可回链")
public class MaskingRuleController {

    private final MaskingRuleService ruleService;

    public MaskingRuleController(MaskingRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    @Operation(summary = "登记遮蔽规则版本（同 ruleCode 版本须递增，旧版保留可回链）")
    public MaskingRuleService.RuleOutcome register(@RequestBody MaskingRuleService.RuleInput in) {
        return ruleService.register(in);
    }

    @GetMapping
    @Operation(summary = "遮蔽规则版本清单（含修订史）")
    public List<MaskingRule> list() {
        return ruleService.list();
    }

    @GetMapping("/active")
    @Operation(summary = "当前生效遮蔽规则")
    public List<MaskingRule> active() {
        return ruleService.activeRules();
    }

    @GetMapping("/entity-types")
    @Operation(summary = "支持的敏感实体类型")
    public Map<String, Object> types() {
        return Map.of("entityTypes", MaskingRuleService.ENTITY_TYPES.stream().sorted().toList(),
                "actions", List.of(MaskingActions.ACTION_FULL_MASK, MaskingActions.ACTION_KEEP_LAST,
                        MaskingActions.ACTION_KEEP_FIRST_LAST));
    }
}
