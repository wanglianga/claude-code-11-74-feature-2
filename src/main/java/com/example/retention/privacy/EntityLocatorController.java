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

/** 实体定位器端：自动定位（含跨段缝合）、识别修正、定位结果与字段位置查询。 */
@RestController
@RequestMapping("/api/privacy/entities")
@Tag(name = "隐私-实体定位器", description = "在不可变原转写上定位敏感实体并回链字段位置；支持跨段与识别修正")
public class EntityLocatorController {

    private final EntityLocatorService locator;

    public EntityLocatorController(EntityLocatorService locator) {
        this.locator = locator;
    }

    @PostMapping("/recordings/{recordingId}/locate")
    @Operation(summary = "对录音当前转写自动定位敏感实体（身份证/银行卡/住址/健康，含跨段缝合）")
    public Map<String, Object> locate(@PathVariable UUID recordingId) {
        return locator.locate(recordingId);
    }

    @PostMapping("/recordings/{recordingId}/corrections")
    @Operation(summary = "识别修正：登记实体新版本并取代旧版（原转写不回写）")
    public Map<String, Object> correct(@PathVariable UUID recordingId,
                                       @RequestBody EntityLocatorService.CorrectionInput in) {
        return locator.correct(recordingId, in);
    }

    @GetMapping("/recordings/{recordingId}")
    @Operation(summary = "录音的敏感实体清单（含修正链与逐处字段位置）")
    public List<Map<String, Object>> list(@PathVariable UUID recordingId) {
        return locator.listEntities(recordingId);
    }
}
