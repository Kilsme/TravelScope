package com.travelscope.service;

import com.travelscope.dto.TokenUsageVO;
import com.travelscope.repository.MessageRepository;
import com.travelscope.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Token 用量报表服务（FR-A01：按用户/按天/按模型聚合，数据源 messages.token_count）
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public TokenUsageService(MessageRepository messageRepository, UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    /**
     * 查询 Token 用量报表
     *
     * @param userId   可选用户过滤（null=全部）
     * @param dateFrom 可选起始日期（null=不限）
     * @param dateTo   可选结束日期（null=不限）
     * @return 聚合行（含 username 展示名）
     */
    public List<TokenUsageVO> query(Long userId, LocalDate dateFrom, LocalDate dateTo) {
        List<MessageRepository.TokenUsageRow> rows =
                messageRepository.aggregateTokenUsage(userId, dateFrom, dateTo);
        // userId → username 一次性查（避免 N+1）
        Map<Long, String> usernames = userRepository.findAll().stream()
                .collect(Collectors.toMap(u -> u.getId(), u -> u.getUsername()));
        List<TokenUsageVO> result = rows.stream()
                .map(r -> new TokenUsageVO(
                        r.getUserId(),
                        usernames.getOrDefault(r.getUserId(), "user-" + r.getUserId()),
                        r.getUsageDate() != null ? r.getUsageDate().toString() : null,
                        r.getModelName(),
                        r.getMessageCount(),
                        r.getTotalTokens()))
                .toList();
        log.info("Token 用量查询: userId={}, dateFrom={}, dateTo={} → {} 行",
                userId, dateFrom, dateTo, result.size());
        return result;
    }
}
