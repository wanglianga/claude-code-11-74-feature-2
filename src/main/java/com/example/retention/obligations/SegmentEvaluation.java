package com.example.retention.obligations;

import com.example.retention.graph.HoldView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 片段级求值结果：全部有效义务、覆盖保全、普通保留到期日、处置资格与阻断原因。 */
public record SegmentEvaluation(
        UUID segmentId,
        UUID recordingId,
        int segmentSeq,
        List<ObligationView> obligations,
        List<HoldView> holds,
        /** 普通保留到期日：已确认义务的最晚到期日；无已确认义务时为 null。 */
        LocalDate normalRetentionUntil,
        boolean eligible,
        List<String> blockReasons) {
}
