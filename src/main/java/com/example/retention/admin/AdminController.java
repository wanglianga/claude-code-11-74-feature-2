package com.example.retention.admin;

import com.example.retention.common.WatermarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 演示管理端：清空全部业务数据，便于验收脚本重复执行。 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "演示管理", description = "仅本地演示：重置全部数据")
public class AdminController {

    private static final String[] TABLES = {
            "execution_ledger", "escalation_record", "disposal_candidate", "disposal_plan",
            "access_snapshot", "ledger_maintenance_log", "obligation_entry", "retention_rule",
            "ingest_conflict", "business_event", "field_classification", "recording_segment",
            "recording_object"};

    private final JdbcTemplate jdbc;
    private final WatermarkService watermark;

    public AdminController(JdbcTemplate jdbc, WatermarkService watermark) {
        this.jdbc = jdbc;
        this.watermark = watermark;
    }

    @DeleteMapping("/reset")
    @Operation(summary = "清空全部业务表并重置水位（演示用）")
    public Map<String, Object> reset() {
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY CASCADE");
        watermark.reset();
        return Map.of("reset", true, "tables", TABLES.length);
    }
}
