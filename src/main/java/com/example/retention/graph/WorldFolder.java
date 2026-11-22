package com.example.retention.graph;

import com.example.retention.ingest.BusinessEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 世界折叠器：业务事件是世界状态的唯一事实来源。
 * 当前状态 = 折叠全部事件；历史复演 = 折叠 seq ≤ 水位 的事件前缀，二者共用同一套转移逻辑，
 * 保证“同一截点得到相同结果”。
 */
@Component
public class WorldFolder {

    public static final String FACT_PRODUCT_TERMINATION = "PRODUCT_TERMINATION";
    public static final String FACT_COLLECTION_CLOSURE = "COLLECTION_CLOSURE";
    public static final String FACT_COMPLAINT = "COMPLAINT";
    public static final String FACT_CASE = "CASE";

    /** 折叠事件序列（必须已按 seq 升序）。 */
    public WorldState fold(List<BusinessEvent> events, long watermark) {
        Map<String, FactView> facts = new LinkedHashMap<>();
        Map<String, LinkView> links = new LinkedHashMap<>();
        Map<String, HoldView> holds = new LinkedHashMap<>();
        int folded = 0;
        for (BusinessEvent e : events) {
            if (e.getSeq() > watermark) {
                break;
            }
            apply(e, facts, links, holds);
            folded++;
        }
        return new WorldState(watermark, facts, links, holds, folded);
    }

    private void apply(BusinessEvent e,
                       Map<String, FactView> facts,
                       Map<String, LinkView> links,
                       Map<String, HoldView> holds) {
        Map<String, Object> p = e.getPayload();
        switch (e.getEventType()) {
            case "PRODUCT_TERMINATED" -> upsertFact(facts, e, FACT_PRODUCT_TERMINATION);
            case "COLLECTION_CLOSED" -> upsertFact(facts, e, FACT_COLLECTION_CLOSURE);
            case "COMPLAINT_FILED" -> upsertFact(facts, e, FACT_COMPLAINT);
            case "CASE_OPENED" -> upsertFact(facts, e, FACT_CASE);
            case "FACT_CORRECTED" -> {
                String key = str(p, "fact_key");
                FactView old = facts.get(key);
                if (old == null) {
                    return;
                }
                LocalDate newEffective = p.containsKey("effective_time")
                        ? toDate(str(p, "effective_time")) : old.effectiveDate();
                String newCase = p.containsKey("case_id") ? str(p, "case_id") : old.caseId();
                String newAccount = p.containsKey("account_id") ? str(p, "account_id") : old.accountId();
                String newStatus = p.containsKey("confirm")
                        ? (bool(p, "confirm") ? "CONFIRMED" : "UNCONFIRMED") : old.status();
                Map<String, Object> attrs = new LinkedHashMap<>(old.attributes());
                attrs.putAll(p);
                facts.put(key, new FactView(key, old.factType(), newStatus, newEffective,
                        newCase, newAccount, attrs, e.getSeq(), old.revision() + 1));
            }
            case "FACT_CONFIRMED" -> {
                String key = str(p, "fact_key");
                FactView old = facts.get(key);
                if (old != null && !old.revoked()) {
                    facts.put(key, new FactView(key, old.factType(), "CONFIRMED", old.effectiveDate(),
                            old.caseId(), old.accountId(), old.attributes(), e.getSeq(), old.revision() + 1));
                }
            }
            case "FACT_REVOKED" -> {
                String key = str(p, "fact_key");
                FactView old = facts.get(key);
                if (old != null) {
                    facts.put(key, new FactView(key, old.factType(), "REVOKED", old.effectiveDate(),
                            old.caseId(), old.accountId(), old.attributes(), e.getSeq(), old.revision() + 1));
                }
            }
            case "LINK_ASSERTED", "LINK_REVOKED" -> {
                UUID segmentId = UUID.fromString(str(p, "segment_id"));
                String factKey = str(p, "fact_key");
                String lk = WorldState.linkKey(segmentId, factKey);
                LinkView old = links.get(lk);
                int revision = old == null ? 1 : old.revision() + 1;
                links.put(lk, new LinkView(segmentId, factKey,
                        "LINK_ASSERTED".equals(e.getEventType()), e.getSeq(), revision));
            }
            case "HOLD_PLACED" -> {
                String holdKey = str(p, "hold_key");
                holds.put(holdKey, new HoldView(holdKey, str(p, "scope_type"), str(p, "scope_ref"),
                        true, e.getSeq(), null));
            }
            case "HOLD_RELEASED" -> {
                String holdKey = str(p, "hold_key");
                HoldView old = holds.get(holdKey);
                if (old != null) {
                    holds.put(holdKey, new HoldView(old.holdKey(), old.scopeType(), old.scopeRef(),
                            false, old.placedSeq(), e.getSeq()));
                }
            }
            default -> {
                // 未知事件类型不改变世界状态，但仍保留在接入账中。
            }
        }
    }

    private void upsertFact(Map<String, FactView> facts, BusinessEvent e, String factType) {
        Map<String, Object> p = e.getPayload();
        String key = str(p, "fact_key");
        FactView old = facts.get(key);
        int revision = old == null ? 1 : old.revision() + 1;
        String status = p.containsKey("confirm") && !bool(p, "confirm") ? "UNCONFIRMED" : "CONFIRMED";
        facts.put(key, new FactView(key, factType, status,
                toDate(e.getBusinessEffectiveTime().toString()),
                strOrNull(p, "case_id"), strOrNull(p, "account_id"),
                new LinkedHashMap<>(p), e.getSeq(), revision));
    }

    public static LocalDate toDate(String iso) {
        if (iso == null) {
            return null;
        }
        if (iso.length() == 10) {
            return LocalDate.parse(iso);
        }
        return Instant.parse(iso).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            throw new IllegalArgumentException("payload 缺少字段: " + key);
        }
        return v.toString();
    }

    public static String strOrNull(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    public static boolean bool(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(v.toString());
    }
}
