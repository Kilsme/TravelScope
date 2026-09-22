package com.travelscope.repository;

import com.travelscope.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Message 仓库（含 FR-A01 Token 用量聚合查询）
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * Token 用量聚合行（按用户/按天/按模型分组）
     */
    interface TokenUsageRow {
        Long getUserId();

        String getModelName();

        java.sql.Date getUsageDate();

        Long getMessageCount();

        Long getTotalTokens();
    }

    /**
     * 按用户+按天+按模型聚合 token 用量（FR-A01 报表数据源）。
     * <p>
     * 支持 userId（可选）/ dateFrom/dateTo（可选）过滤；分组维度 userId+usageDate+modelName。
     * model_name 可能为 NULL（历史数据），COALESCE 归一到 'unknown'。
     * </p>
     */
    @Query(value = """
            SELECT
                m.user_id AS userId,
                COALESCE(m.model_name, 'unknown') AS modelName,
                CAST(m.created_at AS DATE) AS usageDate,
                COUNT(*) AS messageCount,
                COALESCE(SUM(m.token_count), 0) AS totalTokens
            FROM messages m
            WHERE (:userId IS NULL OR m.user_id = :userId)
              AND (CAST(:dateFrom AS DATE) IS NULL OR CAST(m.created_at AS DATE) >= CAST(:dateFrom AS DATE))
              AND (CAST(:dateTo AS DATE) IS NULL OR CAST(m.created_at AS DATE) <= CAST(:dateTo AS DATE))
            GROUP BY m.user_id, COALESCE(m.model_name, 'unknown'), CAST(m.created_at AS DATE)
            ORDER BY usageDate DESC, m.user_id, modelName
            """, nativeQuery = true)
    List<TokenUsageRow> aggregateTokenUsage(
            @Param("userId") Long userId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);
}
