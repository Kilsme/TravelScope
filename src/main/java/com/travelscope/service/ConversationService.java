package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.Message;
import com.travelscope.entity.User;
import com.travelscope.repository.ConversationRepository;
import com.travelscope.repository.MessageRepository;
import com.travelscope.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话服务：匿名用户保障、会话 CRUD、消息持久化
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 会话标题最大长度（首条消息截断生成） */
    private static final int TITLE_MAX_LEN = 30;

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AppProperties appProperties;

    public ConversationService(UserRepository userRepository,
                               ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               AppProperties appProperties) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.appProperties = appProperties;
    }

    /**
     * 获取或创建匿名用户（当前无认证体系，所有会话归属 guest 用户）
     */
    @Transactional
    public User getOrCreateGuestUser() {
        return userRepository.findByUsername(User.GUEST_USERNAME).orElseGet(() -> {
            User guest = new User();
            guest.setUsername(User.GUEST_USERNAME);
            guest.setPassword("N/A");
            guest.setNickname("游客");
            User saved = userRepository.save(guest);
            log.info("已创建匿名用户: id={}, username={}", saved.getId(), saved.getUsername());
            return saved;
        });
    }

    /**
     * 获取用户全部活跃会话
     */
    public List<Conversation> listConversations(Long userId) {
        return conversationRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "active");
    }

    /**
     * 新建会话
     */
    @Transactional
    public Conversation createConversation(Long userId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        return conversationRepository.save(conversation);
    }

    /**
     * 获取用户自己的会话，不存在或无权限时抛 IllegalArgumentException
     */
    public Conversation getOwnedConversation(Long conversationId, Long userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
    }

    /**
     * 获取会话消息列表（按时间升序）
     */
    public List<Message> listMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * 保存用户消息；会话标题为默认值时用首条消息生成标题
     * <p>
     * FR-A01 Token 采集：user 消息按输入文本估算 token（≈字符/4）计入
     * （意图分类 L2/L3 的固定调用成本计入 assistant 侧）。
     * </p>
     */
    @Transactional
    public void saveUserMessage(Conversation conversation, Long userId, String content) {
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(Message.ROLE_USER);
        message.setContent(content);
        message.setModelName(estimateUserModel());
        message.setTokenCount(estimateTokens(content));
        messageRepository.save(message);

        if ("新会话".equals(conversation.getTitle()) && content != null && !content.isBlank()) {
            String title = content.trim();
            conversation.setTitle(title.length() > TITLE_MAX_LEN ? title.substring(0, TITLE_MAX_LEN) : title);
        }
        conversationRepository.save(conversation);
    }

    /**
     * 保存助手回复
     * <p>
     * FR-A01 Token 采集（2026-10-11 P1-K 升级为真实口径）：realTokens 非空时按当轮 agent
     * 事件流内可见模型调用的真实 usage 之和记账（采集点 ModelCallEndEvent，见
     * TurnUsageTracker），modelName 记实际生效模型（fallback 轮记 fallback——修复固定写
     * 主链路模型导致 fallback 轮报表记错对象）；realTokens 为空（网关未回 usage，如
     * GLMFormatter 剥离 stream_options 后）时回落旧估算口径。
     * 意图分类 L2/L3 不在 agent 事件流内，其成本仍按固定 120 token 估算附加（唯一保留的
     * 估算项）；user 消息仍为文本估算（保存时无真实输入 token 可言）。
     * </p>
     */
    @Transactional
    public void saveAssistantMessage(Conversation conversation, Long userId, String content,
                                     Integer realTokens, String actualModelName) {
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setUserId(userId);
        message.setRole(Message.ROLE_ASSISTANT);
        message.setContent(content);
        if (realTokens != null && actualModelName != null) {
            message.setModelName(actualModelName);
            message.setTokenCount(realTokens + INTENT_CLASSIFY_COST_TOKENS);
        } else {
            // 降级：网关未回 usage → 旧估算口径（报表不因升级变差）
            message.setModelName(estimateAssistantModel());
            message.setTokenCount(estimateTokens(content) + INTENT_CLASSIFY_COST_TOKENS);
        }
        messageRepository.save(message);
        conversationRepository.save(conversation);
    }

    /**
     * 更新会话记忆摘要与覆盖进度（2026-10-11 P2-H：升级为「短事务双校验」写入协议）
     * <p>
     * 事务内重读实体再写入——调用方（后台折叠任务）持有的可能是请求域的陈旧实体，
     * 直接 save 会用旧字段覆盖标题等其他列。
     * </p>
     * <p>
     * 双校验（对照 ragent AgentMemoryPipeline「读快照 → 不持锁仲裁 → 短事务双校验」）：
     * ①乐观并发——事务内重读的当前水位 ≠ expectedCovered（折叠起点快照值），说明他方
     * （多实例/并发任务）已推进或改写，放弃本次提交（防 last-write-wins 覆盖、防旧摘要
     * 覆盖新摘要）；②水位单调——newCovered 小于当前水位属回退，拒绝。双过才写。
     * </p>
     *
     * @param expectedCovered 折叠起点读到的水位线（快照）
     * @param newCovered      本次要写回的新水位线
     * @return true=校验通过已提交；false=校验拒绝（调用方按 ABORTED 结算，不重试）
     */
    @Transactional
    public boolean updateSummaryChecked(Long conversationId, String summary,
                                        int expectedCovered, int newCovered) {
        return conversationRepository.findById(conversationId).map(conversation -> {
            int current = conversation.getSummaryCoveredMessages() == null
                    ? 0 : conversation.getSummaryCoveredMessages();
            if (current != expectedCovered || newCovered < current) {
                log.warn("摘要写回双校验拒绝（并发推进/水位回退，放弃提交）: conversationId={} "
                        + "当前水位={} 期望快照={} 新水位={}", conversationId, current,
                        expectedCovered, newCovered);
                return false;
            }
            conversation.setSummary(summary);
            conversation.setSummaryCoveredMessages(newCovered);
            conversationRepository.save(conversation);
            return true;
        }).orElse(false);
    }

    // ==================== FR-A01 Token 估算（轻量近似，真实链路采集的可测近似） ====================

    /** 意图分类每次调用的固定 token 成本（L2 轻量档 ~80 / L3 主链档 ~200，取保守均值） */
    private static final int INTENT_CLASSIFY_COST_TOKENS = 120;

    /** 按文本长度估算 token（中文约 1 字符 ≈ 1 token，英文/数字约 4 字符/token，取保守 1.5 字符/token 折中） */
    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // 混合语种保守估算：字符数 ÷ 1.5，向上取整
        return (int) Math.ceil(text.length() / 1.5);
    }

    /** Token 报表的 model_name 维度：主链路对话模型（模型网关配置，2026-10-05 前为 qwen-plus） */
    private String estimateUserModel() {
        return appProperties.getModelGateway().getModel();
    }

    private String estimateAssistantModel() {
        return appProperties.getModelGateway().getModel();
    }
}
