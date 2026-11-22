package com.example.retention.privacy;

import com.example.retention.access.AccessPolicy;
import com.example.retention.graph.FactView;
import com.example.retention.graph.LinkView;
import com.example.retention.graph.WorldState;
import com.example.retention.ingest.RecordingSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 范围校验器：按角色/用途/案件目的核验“哪些片段允许进入证据视图”。
 * 与调阅投影同语义：只凭显式关系边归并案件，不按账户隐式归并；
 * 质检员等最小权限角色只能看到案件范围内、存在有效关联的投诉相关片段。
 */
@Component
public class ScopeValidator {

    private final AccessPolicy policy;

    public ScopeValidator(AccessPolicy policy) {
        this.policy = policy;
    }

    public record ScopeDecision(boolean rolePurposeGranted, List<UUID> inScopeSegmentIds,
                                List<Map<String, Object>> exclusions) {
    }

    public void requireKnownRole(String role) {
        if (!policy.knownRole(role)) {
            throw com.example.retention.common.ApiException.forbidden("未知角色: " + role);
        }
    }

    /** 角色-用途核验。 */
    public boolean rolePurposeGranted(String role, String purpose) {
        return policy.allowsPurpose(role, purpose);
    }

    /**
     * 计算给定录音各片段是否落在案件范围内（基于指定水位的世界状态）。
     */
    public ScopeDecision evaluate(WorldState world, List<RecordingSegment> segments,
                                  String role, String purpose, List<String> caseScope) {
        requireKnownRole(role);
        if (caseScope == null) {
            throw com.example.retention.common.ApiException.badRequest("案件范围 caseScope 不能为空（可为空数组）");
        }
        boolean granted = policy.allowsPurpose(role, purpose);
        List<UUID> inScope = new ArrayList<>();
        List<Map<String, Object>> exclusions = new ArrayList<>();
        for (RecordingSegment seg : segments) {
            List<String> reasons = new ArrayList<>();
            if (!granted) {
                reasons.add("ROLE_PURPOSE_DENIED: 角色 " + role + " 不可用用途 " + purpose);
            }
            List<LinkView> links = world.activeLinksOf(seg.getId());
            List<FactView> facts = links.stream()
                    .map(l -> world.fact(l.factKey()))
                    .filter(f -> f != null && !f.revoked())
                    .toList();
            if (links.isEmpty()) {
                reasons.add("NO_ACTIVE_LINK: 片段与任何业务事实均无显式关联（不按账户归并）");
            } else {
                Set<String> factCases = new LinkedHashSet<>();
                facts.stream().map(FactView::caseId).filter(java.util.Objects::nonNull)
                        .forEach(factCases::add);
                boolean hit = factCases.stream().anyMatch(caseScope::contains);
                if (!hit) {
                    reasons.add("OUT_OF_CASE_SCOPE: 片段关联案件 " + factCases
                            + " 不在视图案件范围 " + caseScope);
                }
            }
            if (reasons.isEmpty()) {
                inScope.add(seg.getId());
            } else {
                Map<String, Object> ex = new LinkedHashMap<>();
                ex.put("segmentId", seg.getId().toString());
                ex.put("segmentSeq", seg.getSeq());
                ex.put("reasons", reasons);
                exclusions.add(ex);
            }
        }
        return new ScopeDecision(granted, inScope, exclusions);
    }

    /** 案件范围签名：范围变化即签名变化（排序后规范化）。 */
    public static String scopeSignature(List<String> caseScope) {
        return caseScope == null ? "[]" : new LinkedHashSet<>(caseScope).stream()
                .sorted().collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
