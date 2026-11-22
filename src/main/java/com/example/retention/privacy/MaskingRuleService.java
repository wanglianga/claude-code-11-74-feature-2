package com.example.retention.privacy;

import com.example.retention.common.ApiException;
import com.example.retention.common.WatermarkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 遮蔽规则版本库：登记不可变规则版本并维护修订史。
 * 同一 ruleCode 的版本号必须递增；登记新版本后旧版本置 SUPERSEDED 但仍可被历史视图按版本回链。
 * 配置以规范化 JSON 计算指纹，视图绑定 configHash，规则修订后物化视图据此准确失效。
 */
@Service
public class MaskingRuleService {

    public static final Set<String> ENTITY_TYPES =
            Set.of("ID_CARD", "BANK_CARD", "ADDRESS", "HEALTH");

    private final MaskingRuleRepository ruleRepository;
    private final WatermarkService watermark;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper canonicalMapper;

    public MaskingRuleService(MaskingRuleRepository ruleRepository, WatermarkService watermark,
                              ApplicationEventPublisher publisher) {
        this.ruleRepository = ruleRepository;
        this.watermark = watermark;
        this.publisher = publisher;
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record RuleInput(String ruleCode, int version, String changeNote,
                            Map<String, Map<String, Object>> typeConfig) {
    }

    public record RuleOutcome(UUID ruleId, String ruleCode, int version, long registeredSeq,
                              String configHash, String message) {
    }

    @Transactional
    public RuleOutcome register(RuleInput in) {
        if (in.ruleCode() == null || in.ruleCode().isBlank()) {
            throw ApiException.badRequest("ruleCode 不能为空");
        }
        if (in.typeConfig() == null || in.typeConfig().isEmpty()) {
            throw ApiException.badRequest("规则配置不能为空");
        }
        for (String t : in.typeConfig().keySet()) {
            if (!ENTITY_TYPES.contains(t)) {
                throw ApiException.badRequest("未知实体类型: " + t);
            }
            Object action = in.typeConfig().get(t).get("action");
            if (action == null || !Set.of(MaskingActions.ACTION_FULL_MASK, MaskingActions.ACTION_KEEP_LAST,
                    MaskingActions.ACTION_KEEP_FIRST_LAST).contains(action.toString())) {
                throw ApiException.badRequest("实体类型 " + t + " 的 action 不合法: " + action);
            }
        }

        List<MaskingRule> existing = ruleRepository.findByRuleCodeOrderByVersion(in.ruleCode());
        if (existing.stream().anyMatch(r -> r.getVersion() == in.version())) {
            throw ApiException.conflict("遮蔽规则版本已存在: " + in.ruleCode() + " v" + in.version());
        }
        if (!existing.isEmpty() && in.version() <= existing.get(existing.size() - 1).getVersion()) {
            throw ApiException.badRequest("遮蔽规则版本必须递增");
        }

        Map<String, Object> config = new LinkedHashMap<>(in.typeConfig());
        String hash = hash(config);
        MaskingRule rule = new MaskingRule();
        rule.setId(UUID.randomUUID());
        rule.setRegisteredSeq(watermark.next());
        rule.setRuleCode(in.ruleCode());
        rule.setVersion(in.version());
        rule.setRuleConfig(config);
        rule.setConfigHash(hash);
        rule.setChangeNote(in.changeNote() == null ? "" : in.changeNote());
        rule.setStatus(MaskingRule.STATUS_ACTIVE);
        rule.setCreatedAt(Instant.now());
        ruleRepository.save(rule);

        for (MaskingRule old : existing) {
            old.setStatus(MaskingRule.STATUS_SUPERSEDED);
            ruleRepository.save(old);
        }
        // 发布规则修订事件：物化视图据此准确失效（同事务同步监听）
        publisher.publishEvent(new PrivacyEvents.MaskingRuleChanged(
                rule.getRuleCode(), rule.getVersion(), rule.getRegisteredSeq()));
        return new RuleOutcome(rule.getId(), rule.getRuleCode(), rule.getVersion(),
                rule.getRegisteredSeq(), hash,
                existing.isEmpty() ? "遮蔽规则首版已登记" : "遮蔽规则新版本已登记，旧版保留可回链");
    }

    String hash(Map<String, Object> config) {
        try {
            String canonical = canonicalMapper.writeValueAsString(config);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("无法计算规则配置指纹", ex);
        }
    }

    /** 当前生效（ACTIVE）规则。 */
    @Transactional(readOnly = true)
    public List<MaskingRule> activeRules() {
        return ruleRepository.findByStatusOrderByRuleCodeAscVersionAsc(MaskingRule.STATUS_ACTIVE);
    }

    /** 某水位下已登记的规则（历史复演）。 */
    @Transactional(readOnly = true)
    public List<MaskingRule> rulesAt(long wm) {
        return ruleRepository.findByRegisteredSeqLessThanEqualOrderByRuleCodeAscVersionAsc(wm);
    }

    @Transactional(readOnly = true)
    public List<MaskingRule> list() {
        return ruleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public MaskingRule require(UUID ruleId) {
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("遮蔽规则版本不存在: " + ruleId));
    }

    /** 规则视图（回链用）。 */
    static Map<String, Object> pin(MaskingRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", r.getId().toString());
        m.put("ruleCode", r.getRuleCode());
        m.put("version", r.getVersion());
        m.put("registeredSeq", r.getRegisteredSeq());
        m.put("configHash", r.getConfigHash());
        return m;
    }
}
