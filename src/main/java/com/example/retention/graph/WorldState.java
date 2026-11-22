package com.example.retention.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 某一水位下折叠得到的世界状态：事实、关系边、保全。 */
public record WorldState(
        long watermark,
        Map<String, FactView> facts,
        Map<String, LinkView> links,
        Map<String, HoldView> holds,
        int eventsFolded) {

    public static String linkKey(UUID segmentId, String factKey) {
        return segmentId + "|" + factKey;
    }

    public FactView fact(String factKey) {
        return facts.get(factKey);
    }

    /** 片段当前有效的关系边。 */
    public List<LinkView> activeLinksOf(UUID segmentId) {
        return links.values().stream()
                .filter(l -> l.active() && l.segmentId().equals(segmentId))
                .toList();
    }

    public Collection<HoldView> allHolds() {
        return holds.values();
    }

    public static WorldState empty(long watermark) {
        return new WorldState(watermark, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), 0);
    }
}
