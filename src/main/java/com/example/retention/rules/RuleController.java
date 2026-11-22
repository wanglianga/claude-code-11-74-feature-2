package com.example.retention.rules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 规则目录端。 */
@RestController
@RequestMapping("/api/rules")
@Tag(name = "规则目录", description = "适用事实、起算事件、保留期限、适用区间、用途、字段集合；版本与追溯")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    @Operation(summary = "登记规则版本（追溯版本会对适用区间内既有事实重放义务传播）")
    public RuleService.RuleOutcome register(@RequestBody RuleService.RuleInput input) {
        return ruleService.register(input);
    }

    @GetMapping
    @Operation(summary = "规则目录清单（含全部版本）")
    public List<RetentionRule> list() {
        return ruleService.list();
    }
}
