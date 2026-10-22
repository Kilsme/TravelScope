package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.dto.SummaryResult;
import com.travelscope.entity.Conversation;
import com.travelscope.entity.Message;
import com.travelscope.repository.ConversationRepository;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆摘要服务（2026-09-29 失忆修复：滚动摘要，增量折叠）
 * <p>
 * 滑动窗口（最近 10 轮原文）装不下整个会话，窗口外的更早轮次在本服务折叠成
 * conversations.summary 长期携带。触发条件：消息总数 - 已覆盖条数 ≥ intervalRounds×2
 * （即每满 10 轮），由 ChatService 在每轮回复落库后与本轮历史注入时调用检查。
 * </p>
 * <p>
 * 折叠为后台异步（fire-and-forget，绝不阻塞对话链路）；轻量模型（deepseek-v4-flash-0731）
 * 低成本调用，
 * 仿 {@link com.travelscope.agent.LightweightIntentClassifier} 模式：每次新建无工具
 * ReActAgent、独立 sessionId 不污染主 Agent 的 Redis 状态、结构化 + 纯文本双路解析。
 * 失败静默降级（log.warn 保留原状，不抛出），下个到期点自动重试——与项目 Redis 类
 * 组件的容错惯例一致。
 * </p>
 * <p>
 * 无缺口保证：折叠点 covered=T 恒不落后于后续各轮的窗口起点（窗口只取最近 20 条），
 * 摘要与窗口约有 10 轮重叠（无害，利于连贯）；偶发折叠失败由 ChatHistoryRenderer
 * 的窗口前移自愈 + 本服务的补折触发兜底。
 * </p>
 * <p>
 * <b>写入协议（2026-10-11 P2-H，对照 ragent AgentMemoryPipeline 3.1 四要素）</b>：
 * ①<b>水位线</b>：{@code summaryCoveredMessages} 划分「已固化/待折叠」边界，水位前不重复
 * 折叠；②<b>事务外仲裁</b>：读快照 + 模型慢调用全部在事务外（{@link #doFold} 非事务），
 * 不占库锁；③<b>短事务双校验</b>：写回经
 * {@link ConversationService#updateSummaryChecked}——事务内重读并校验「当前水位=折叠起点
 * 快照 + 新水位不回退」，他方（多实例/并发任务）已推进则放弃提交（防 last-write-wins、
 * 防旧摘要覆盖新摘要）；④<b>失败结算</b>：{@link FoldOutcome} 显式结算——空批次推水位记
 * DROPPED_BLANK（「推水位 ≠ 折叠成功」，即 ragent 警示的 DROPPED 推水位把失败报成成功之
 * 坑的 TravelScope 对应物）；校验拒绝记 ABORTED_CONCURRENT 不重试；异常记 FAILED 水位
 * 不动、下到期点重试。
 * </p>
 */
@Service
public class ConversationSummaryService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.travelscope.assessment.ObservationMiddleware observationMiddleware;

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    /** 摘要折叠专用调度池：每 10 轮一次的低频后台任务，2 线程足够 */
    private final Scheduler summaryScheduler =
            Schedulers.newBoundedElastic(2, 200, "summary-gen", 60, true);

    /** 折叠进行中的会话（防同会话并发双折：浪费一次轻量模型调用且 last-write-wins 互相覆盖） */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final Model summaryModel;
    private final AppProperties.ChatHistoryConfig config;

    public ConversationSummaryService(ConversationService conversationService,
                                      ConversationRepository conversationRepository,
                                      @Qualifier("gatewayChatModel") Model summaryModel,
                                      AppProperties appProperties) {
        this.conversationService = conversationService;
        this.conversationRepository = conversationRepository;
        this.summaryModel = summaryModel;
        this.config = appProperties.getChatHistory();
    }

    /** 摘要系统提示词模板（%d = summary.maxChars 软上限，硬截断在落库前执行） */
    private static final String SYS_PROMPT_TEMPLATE = """
            你是旅行规划助手的历史记忆压缩器。请把「已有记忆摘要」与「新增对话轮次」合并成一份新的记忆摘要，\
            作为后续对话的长期上下文。

            要求：
            1. 保留所有关键事实：用户偏好与画像、目的地/出发地、行程时间与天数、预算、人数、\
            交通/住宿/景点的已定决策、已确认的结论与待办事项
            2. 去重合并；新旧信息冲突时以最新为准
            3. 条目式中文，简洁准确，总长不超过 %d 字
            4. 只输出摘要本身，不要任何问候、解释或额外说明
            """;

    /**
     * 折叠结算（2026-10-11 P2-H 写入协议：「推水位 ≠ 折叠成功」，结算类型显式化）
     */
    enum FoldOutcome {
        /** 模型产出摘要 + 双校验写回通过 */
        COMMITTED,
        /** 空批次结算：新增消息全空内容，只推水位不调模型（DROPPED 式结算——非折叠成功） */
        DROPPED_BLANK,
        /** 写回被短事务双校验拒绝（他方已推进水位线，不重试） */
        ABORTED_CONCURRENT,
        /** 未执行（条件不满足/模型空结果），水位不动 */
        SKIPPED,
        /** 异常失败，水位不动，下个到期点重试 */
        FAILED
    }

    /**
     * 到期即异步折叠（fire-and-forget）：消息总数 - 已覆盖 ≥ intervalRounds×2 时触发。
     * 调用点：每轮回复落库后（handleComplete/handleError）+ 历史注入时（缺口自愈补折）。
     * 预检在调用方线程同步执行（2 次索引查询，与 runAgent 内已有的 listMessages 同量级）。
     */
    public void maybeSummarizeAsync(Long conversationId, Long userId) { maybeSummarizeAsync(conversationId,userId,null); }
    public void maybeSummarizeAsync(Long conversationId,Long userId,com.travelscope.assessment.ObservationContext observation) {
        if (!tryBeginFold(conversationId)) {
            return;
        }
        Mono.fromCallable(() -> doFold(conversationId, userId, observation))
                .subscribeOn(summaryScheduler)
                .subscribe(outcome -> log.debug("记忆摘要结算: conversationId={}, outcome={}",
                                conversationId, outcome),
                        e -> inFlight.remove(conversationId));
    }

    /**
     * 触发预检 + 占位：enabled 检查、到期判定、inFlight 防重。
     *
     * @return 应执行折叠时 true（已加入 inFlight，调用方须保证最终执行/释放）
     */
    boolean tryBeginFold(Long conversationId) {
        AppProperties.SummaryConfig cfg = config.getSummary();
        if (!config.isEnabled() || !cfg.isEnabled()) {
            return false;
        }
        try {
            List<Message> messages = conversationService.listMessages(conversationId);
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null) {
                return false;
            }
            int covered = coveredOf(conv);
            if (!shouldFold(messages.size(), covered, cfg.getIntervalRounds())) {
                return false;
            }
            if (!inFlight.add(conversationId)) {
                return false;
            }
            log.info("记忆摘要到期，后台折叠: conversationId={}, 消息 {} 条, 已覆盖 {} 条",
                    conversationId, messages.size(), covered);
            return true;
        } catch (Exception e) {
            log.warn("记忆摘要触发检查失败（忽略）: conversationId={}, 原因: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 执行一次增量折叠（后台线程）：messages[covered..T) 渲染后并入已有摘要，
     * 成功则经「短事务双校验」落库 summary + covered=T（P2-H：模型慢调用在事务外仲裁，
     * 仅写回进短事务；校验拒绝说明他方已推进水位线，按 ABORTED_CONCURRENT 结算不重试）。
     * 所有异常吞掉——折叠失败不影响主链路，保留原状等下个到期点重试。
     */
    FoldOutcome doFold(Long conversationId, Long userId) { return doFold(conversationId,userId,null); }
    FoldOutcome doFold(Long conversationId,Long userId,com.travelscope.assessment.ObservationContext observation) {
        try {
            AppProperties.SummaryConfig cfg = config.getSummary();
            // 折叠前重读：预检与实际执行之间可能有新消息落库，以最新为准
            List<Message> messages = conversationService.listMessages(conversationId);
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null || messages.isEmpty()) {
                return FoldOutcome.SKIPPED;
            }
            // 水位线快照：本次折叠的起点，写回时作双校验的期望值
            int covered = coveredOf(conv);
            if (covered >= messages.size()) {
                return FoldOutcome.SKIPPED;
            }
            String newRounds = renderFoldInput(messages, covered, cfg);
            if (newRounds.isBlank()) {
                // 空批次 DROPPED 式结算：只推水位不调模型（显式非折叠成功——「没有待处理
                // ≠ 处理成功」，见类 javadoc 协议段）；同样经双校验防并发覆盖
                boolean committed = conversationService.updateSummaryChecked(
                        conversationId, conv.getSummary(), covered, messages.size());
                if (!committed) {
                    log.warn("空批次水位推进被双校验拒绝（并发）: conversationId={}", conversationId);
                    return FoldOutcome.ABORTED_CONCURRENT;
                }
                log.info("记忆摘要空批次结算（DROPPED_BLANK：仅推进水位，未折叠）: conversationId={}",
                        conversationId);
                return FoldOutcome.DROPPED_BLANK;
            }
            String prompt = buildFoldPrompt(conv.getSummary(), newRounds);
            String summary = callSummarizer(prompt, String.valueOf(userId), conversationId, cfg, observation);
            if (summary == null || summary.isBlank()) {
                log.warn("记忆摘要生成为空，保留原状（下个到期点重试）: conversationId={}", conversationId);
                return FoldOutcome.SKIPPED;
            }
            if (summary.length() > cfg.getMaxChars()) {
                summary = summary.substring(0, cfg.getMaxChars()) + "…（截断）";
            }
            boolean committed = conversationService.updateSummaryChecked(
                    conversationId, summary, covered, messages.size());
            if (!committed) {
                log.warn("记忆摘要写回被双校验拒绝（他方已推进水位线，不重试）: conversationId={}",
                        conversationId);
                return FoldOutcome.ABORTED_CONCURRENT;
            }
            log.info("记忆摘要折叠完成: conversationId={}, 覆盖 {} 条消息, 摘要 {} 字符",
                    conversationId, messages.size(), summary.length());
            return FoldOutcome.COMMITTED;
        } catch (Exception e) {
            log.warn("记忆摘要折叠失败（保留原状，下个到期点重试）: conversationId={}, 原因: {}",
                    conversationId, e.getMessage());
            return FoldOutcome.FAILED;
        } finally {
            inFlight.remove(conversationId);
        }
    }

    /** 到期判定：消息总数 - 已覆盖 ≥ intervalRounds×2（1 轮 = 2 条消息） */
    static boolean shouldFold(int totalMessages, int covered, int intervalRounds) {
        return totalMessages - covered >= intervalRounds * 2;
    }

    /**
     * 折叠输入渲染：messages[covered..T)，自适应单条截断——条数多时按总量上限收紧
     * （存量长会话首次补折可能上百条，固定 600 字/条会超出模型上下文）；
     * 单条下限 100 字防退化成无法提取事实的碎片。
     */
    static String renderFoldInput(List<Message> messages, int covered, AppProperties.SummaryConfig cfg) {
        List<Message> slice = messages.subList(Math.min(covered, messages.size()), messages.size());
        if (slice.isEmpty()) {
            return "";
        }
        int perMsg = (int) Math.max(100L, Math.min(cfg.getInputMaxCharsPerMessage(),
                (long) cfg.getInputTotalMaxChars() / slice.size()));
        StringBuilder sb = new StringBuilder();
        for (Message m : slice) {
            String role = Message.ROLE_USER.equals(m.getRole()) ? "用户" : "助手";
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > perMsg) {
                content = content.substring(0, perMsg) + "…（截断）";
            }
            sb.append(role).append("：").append(content).append('\n');
        }
        return sb.toString();
    }

    /** 折叠提示词：已有摘要（首次折叠时无）+ 新增轮次 */
    static String buildFoldPrompt(String oldSummary, String newRounds) {
        StringBuilder sb = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("【已有记忆摘要】\n").append(oldSummary.trim()).append("\n\n");
        }
        sb.append("【新增对话轮次】\n").append(newRounds);
        return sb.toString();
    }

    /**
     * 调用摘要模型（轻量档 deepseek-v4-flash-0731）：结构化解析 + 纯文本兜底双路
     * （轻量模型对结构化输出遵循度不稳定，与 L2 意图分类同款双路，见 LightweightIntentClassifier）
     */
    private String callSummarizer(String prompt, String userId, Long conversationId,
                                  AppProperties.SummaryConfig cfg, com.travelscope.assessment.ObservationContext observation) {
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                // 独立会话后缀，不污染主 Agent 的 Redis 状态（conv-{id}）
                .sessionId("conv-" + conversationId + "-summary")
                .build();
        if (observation != null) ctx.put(com.travelscope.assessment.ObservationContext.KEY, observation.withPurpose("SUMMARY"));
        ReActAgent summarizer = ReActAgent.builder()
                .middlewares(observationMiddleware == null ? java.util.List.of() : java.util.List.of(observationMiddleware))
                .name("conversation-summarizer")
                .sysPrompt(SYS_PROMPT_TEMPLATE.formatted(cfg.getMaxChars()))
                .model(summaryModel)
                .maxIters(2)
                .build();
        Msg reply = summarizer.call(prompt, SummaryResult.class, ctx)
                .block(Duration.ofSeconds(cfg.getTimeoutSeconds()));
        if (reply == null) {
            return null;
        }
        try {
            SummaryResult result = reply.getStructuredData(SummaryResult.class);
            if (result != null && result.summary != null && !result.summary.isBlank()) {
                return result.summary;
            }
        } catch (Exception ignored) {
            // metadata 无结构化键（轻量模型常见），走纯文本
        }
        return reply.getTextContent();
    }

    private static int coveredOf(Conversation conv) {
        return conv.getSummaryCoveredMessages() == null ? 0 : conv.getSummaryCoveredMessages();
    }
}
