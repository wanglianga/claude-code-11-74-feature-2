package com.example.retention.common;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全局证据水位：所有会改变“世界状态”的行（事件、规则、义务账条目）都从同一序列取号，
 * 快照只需记录一个水位值即可固定当时的证据截点。
 */
@Service
public class WatermarkService {

    private final JdbcTemplate jdbc;

    public WatermarkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS wm_seq START WITH 1");
    }

    /** 取下一个水位号（写入侧使用）。 */
    public long next() {
        Long v = jdbc.queryForObject("SELECT nextval('wm_seq')", Long.class);
        return v == null ? 0 : v;
    }

    /** 当前水位（已分配的最大序号）。 */
    public long now() {
        Long v = jdbc.queryForObject("SELECT last_value FROM wm_seq", Long.class);
        return v == null ? 0 : v;
    }

    public void reset() {
        jdbc.execute("ALTER SEQUENCE wm_seq RESTART WITH 1");
    }
}
