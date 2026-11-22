package com.example.retention.graph;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 一次变更的影响面：受影响的片段与整对象，用于义务账补偿传播与候选撤销。 */
public record ChangeImpact(
        Set<UUID> segmentIds,
        Set<UUID> recordingIds,
        String reason) {

    public static ChangeImpact of(String reason) {
        return new ChangeImpact(new LinkedHashSet<>(), new LinkedHashSet<>(), reason);
    }

    public ChangeImpact withSegment(UUID segmentId, UUID recordingId) {
        segmentIds.add(segmentId);
        recordingIds.add(recordingId);
        return this;
    }

    public ChangeImpact withRecording(UUID recordingId) {
        recordingIds.add(recordingId);
        return this;
    }

    public boolean isEmpty() {
        return segmentIds.isEmpty() && recordingIds.isEmpty();
    }
}
