package com.example.retention.obligations;

import java.util.List;
import java.util.UUID;

/** 整对象求值结果：只有全部片段均具备处置资格时，整对象才成为候选。 */
public record RecordingEvaluation(
        UUID recordingId,
        boolean eligible,
        List<SegmentEvaluation> segments,
        List<String> blockReasons) {
}
