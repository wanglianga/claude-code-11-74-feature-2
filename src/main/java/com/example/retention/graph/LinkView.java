package com.example.retention.graph;

import java.util.UUID;

/** 证据关系边：片段与业务事实之间的显式关联（不按账户等属性隐式归并）。 */
public record LinkView(
        UUID segmentId,
        String factKey,
        boolean active,
        long lastChangeSeq,
        int revision) {
}
