package com.example.retention.rules;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionRuleRepository extends JpaRepository<RetentionRule, UUID> {
    List<RetentionRule> findByRegisteredSeqLessThanEqual(long seq);
    List<RetentionRule> findByRuleCodeOrderByVersion(String ruleCode);
}
