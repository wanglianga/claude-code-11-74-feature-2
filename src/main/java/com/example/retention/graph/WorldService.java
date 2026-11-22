package com.example.retention.graph;

import com.example.retention.common.WatermarkService;
import com.example.retention.ingest.BusinessEventRepository;
import com.example.retention.rules.RetentionRule;
import com.example.retention.rules.RetentionRuleRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 世界状态与规则的读取入口：当前截点或指定水位。 */
@Service
public class WorldService {

    private final BusinessEventRepository eventRepository;
    private final RetentionRuleRepository ruleRepository;
    private final WorldFolder folder;
    private final WatermarkService watermark;

    public WorldService(BusinessEventRepository eventRepository,
                        RetentionRuleRepository ruleRepository,
                        WorldFolder folder,
                        WatermarkService watermark) {
        this.eventRepository = eventRepository;
        this.ruleRepository = ruleRepository;
        this.folder = folder;
        this.watermark = watermark;
    }

    /** 当前水位的世界状态。 */
    @Transactional(readOnly = true)
    public WorldState currentWorld() {
        return worldAt(watermark.now());
    }

    /** 指定水位截点的世界状态（历史复演用）。 */
    @Transactional(readOnly = true)
    public WorldState worldAt(long wm) {
        return folder.fold(eventRepository.findBySeqLessThanEqualOrderBySeq(wm), wm);
    }

    /** 全部已登记规则（核心按水位自行过滤可见性）。 */
    public List<RetentionRule> allRules() {
        return ruleRepository.findAll();
    }

    /** 某水位下已登记的规则。 */
    public List<RetentionRule> rulesAt(long wm) {
        return ruleRepository.findByRegisteredSeqLessThanEqual(wm);
    }

    public long now() {
        return watermark.now();
    }
}
