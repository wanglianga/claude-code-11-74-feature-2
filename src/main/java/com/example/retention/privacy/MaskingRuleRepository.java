package com.example.retention.privacy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaskingRuleRepository extends JpaRepository<MaskingRule, UUID> {
    List<MaskingRule> findByRuleCodeOrderByVersion(String ruleCode);
    List<MaskingRule> findByStatusOrderByRuleCodeAscVersionAsc(String status);
    List<MaskingRule> findByRegisteredSeqLessThanEqualOrderByRuleCodeAscVersionAsc(long wm);
    Optional<MaskingRule> findByRuleCodeAndVersion(String ruleCode, int version);
}
