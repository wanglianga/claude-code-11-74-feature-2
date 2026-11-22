package com.example.retention.privacy;

import java.util.Map;

/**
 * 遮蔽动作引擎：按规则配置对一段原文做字符级遮蔽，输出与原文等长或固定占位的遮蔽串。
 * 动作：
 * <ul>
 *   <li>FULL_MASK：整段替换为固定占位符；</li>
 *   <li>KEEP_LAST：保留末 keep 个字符，其余以 placeholder 逐字替换；</li>
 *   <li>KEEP_FIRST_LAST：保留首尾各 keep 个字符，中间替换。</li>
 * </ul>
 * 引擎是纯函数：同一（原文, 配置）必然得到同一遮蔽串，供视图复演与完整性核验。
 */
public final class MaskingActions {

    public static final String ACTION_FULL_MASK = "FULL_MASK";
    public static final String ACTION_KEEP_LAST = "KEEP_LAST";
    public static final String ACTION_KEEP_FIRST_LAST = "KEEP_FIRST_LAST";

    private MaskingActions() {
    }

    @SuppressWarnings("unchecked")
    public static String mask(String coveredText, Map<String, Object> typeConfig) {
        if (typeConfig == null) {
            return coveredText;
        }
        String action = String.valueOf(typeConfig.getOrDefault("action", ACTION_FULL_MASK));
        String placeholder = String.valueOf(typeConfig.getOrDefault("placeholder", "*"));
        int len = coveredText.length();
        return switch (action) {
            case ACTION_FULL_MASK -> placeholder;
            case ACTION_KEEP_LAST -> {
                int keep = intOr(typeConfig, "keep", 4);
                if (len <= keep) {
                    yield placeholder.repeat(len);
                }
                yield placeholder.repeat(len - keep) + coveredText.substring(len - keep);
            }
            case ACTION_KEEP_FIRST_LAST -> {
                int keep = intOr(typeConfig, "keep", 1);
                if (len <= keep * 2) {
                    yield placeholder.repeat(len);
                }
                yield coveredText.substring(0, keep)
                        + placeholder.repeat(len - keep * 2)
                        + coveredText.substring(len - keep);
            }
            default -> throw new IllegalArgumentException("未知遮蔽动作: " + action);
        };
    }

    private static int intOr(Map<String, Object> cfg, String key, int dflt) {
        Object v = cfg.get(key);
        return v instanceof Number n ? n.intValue() : dflt;
    }
}
