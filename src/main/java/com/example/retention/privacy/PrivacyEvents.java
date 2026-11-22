package com.example.retention.privacy;

import java.util.UUID;

/** 隐私视图域的失效事件：由写入侧发布，{@link EvidenceViewService} 同步监听以准确失效物化视图。 */
public final class PrivacyEvents {

    private PrivacyEvents() {
    }

    /** 遮蔽规则登记新版本。 */
    public record MaskingRuleChanged(String ruleCode, int newVersion, long registeredSeq) {
    }

    /** 敏感实体识别修正（新版本取代旧版）。 */
    public record SensitiveEntityCorrected(UUID recordingId, UUID entityId, int newRevision) {
    }

    /** 转写更正：新录音版本取代旧版本（原证据不删除，仅置 SUPERSEDED）。 */
    public record RecordingSuperseded(UUID oldRecordingId, UUID newRecordingId, long newSeq) {
    }
}
