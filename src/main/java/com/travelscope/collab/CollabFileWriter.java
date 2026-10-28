package com.travelscope.collab;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * 工作区契约文件统一写入入口（S0.3）：先校验、再 tmp+move 原子落盘。
 * <p>
 * 写入三段式：
 * <ol>
 *   <li>文件名-类型一致性：9 个契约文件名与 DTO 类型强绑定（conv-97 教训——
 *       task_backlog 的内容绝不能落进 itinerary_draft.json），不匹配即拒写；
 *       白名单外的文件名放行（仅记 debug，不阻断未来扩展）</li>
 *   <li>{@link CollabSchemaValidator} 全量校验：失败抛 {@link CollabValidationException}
 *       且不写盘（目标文件保持原样）</li>
 *   <li>原子落盘：同目录唯一 tmp 文件 + {@code ATOMIC_MOVE} 替换，杜绝半文件；
 *       失败清理 tmp 残留</li>
 * </ol>
 * 不提供任何绕过校验的 raw write——所有写盘必经校验（单一生产者原则的代码保证）。
 * </p>
 */
@Service
public class CollabFileWriter {

    private static final Logger log = LoggerFactory.getLogger(CollabFileWriter.class);

    /** 契约文件名 → 期望 DTO 类型（命中映射强校验；未命中放行仅记 debug） */
    private static final Map<String, Class<?>> EXPECTED_TYPE_BY_FILENAME = Map.ofEntries(
            Map.entry("intake_result.json", IntakeResult.class),
            Map.entry("task_backlog.json", TaskBacklog.class),
            Map.entry("poi_shortlist.json", PoiShortlist.class),
            Map.entry("route_plan.json", RoutePlan.class),
            Map.entry("transport_selection.json", TransportSelection.class),
            Map.entry("hotel_selection.json", HotelSelection.class),
            Map.entry("execution_result.json", ExecutionResult.class),
            Map.entry("itinerary_draft.json", ItineraryDraft.class),
            Map.entry("review_report.json", ReviewReport.class),
            Map.entry("review_passed.json", ReviewPassed.class),
            Map.entry("planner_stage_result.json", AgentStageResult.class),
            Map.entry("reviewer_stage_result.json", AgentStageResult.class),
            Map.entry("session_meta.json", SessionMeta.class));

    /** 工作区根路径（travelscope.agentscope.workspace-path；S0.5 迁移后由 TaskWorkspace 统一解析） */
    private final String workspaceRoot;

    public CollabFileWriter(@Value("${travelscope.agentscope.workspace-path}") String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 校验并原子写入契约文件。
     *
     * @param dir      目标目录（不存在时自动创建）
     * @param fileName 文件名（9 个契约文件名之一时强校验 DTO 类型匹配）
     * @param dto      契约 DTO（序列化后须经 {@link CollabSchemaValidator} 全量校验）
     * @return 落盘文件路径
     * @throws CollabValidationException 类型不匹配或 Schema 校验失败（目标文件不被污染）
     * @throws UncheckedIOException      磁盘写入失败
     */
    public Path write(Path dir, String fileName, Object dto) {
        long start = System.nanoTime();
        rejectOnTypeMismatch(fileName, dto);

        String json = JSON.toJSONString(dto);
        try {
            CollabSchemaValidator.validate(json, dto.getClass());
        } catch (CollabValidationException e) {
            log.warn("契约校验失败已拒写: file={}, 原因={}", fileName, e.getMessage());
            throw e;
        }

        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            // 唯一后缀 tmp：同目录并发写互不踩踏；同目录保证同文件系统，ATOMIC_MOVE 才成立
            Path tmp = dir.resolve(fileName + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);   // move 成功则无 tmp；失败时清理半文件
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("契约文件已写入: file={}, size={}B, duration={}ms",
                    target, json.getBytes(StandardCharsets.UTF_8).length, ms);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("写入契约文件失败: " + dir.resolve(fileName), e);
        }
    }

    /**
     * 删除契约文件（不存在静默跳过）。用途：新一轮规划编排启动时清除上一轮 review 产物——
     * review_passed.json 有「已存在不被覆盖」的粘滞语义，跨轮残留会把新一轮规则质检顶包放行。
     */
    public boolean delete(Path dir, String fileName) {
        try {
            boolean removed = Files.deleteIfExists(dir.resolve(fileName));
            if (removed) {
                log.info("契约文件已删除: file={}", dir.resolve(fileName));
            }
            return removed;
        } catch (IOException e) {
            throw new UncheckedIOException("删除契约文件失败: " + dir.resolve(fileName), e);
        }
    }

    /**
     * 会话协作目录解析：{@code {workspaceRoot}/{userId}/tasks/{sessionId}}
     * （与 harness 用户目录约定对齐；S0.5 迁移后改由 TaskWorkspace 统一提供）
     */
    public Path resolveSessionDir(String userId, String sessionId) {
        return Path.of(workspaceRoot, String.valueOf(userId), "tasks", sessionId);
    }

    private void rejectOnTypeMismatch(String fileName, Object dto) {
        Class<?> expected = EXPECTED_TYPE_BY_FILENAME.get(fileName);
        if (expected == null) {
            log.debug("非契约文件名（放行，仅做结构校验）: {}", fileName);
            return;
        }
        if (!expected.isInstance(dto)) {
            String reason = "[" + CollabFileWriter.class.getSimpleName() + "] file=" + fileName
                    + " 类型不匹配: 期望 " + expected.getSimpleName()
                    + "，实际 " + dto.getClass().getSimpleName() + "；已拒写";
            log.warn("文件名-类型不匹配已拒写: file={}, 期望={}, 实际={}",
                    fileName, expected.getSimpleName(), dto.getClass().getSimpleName());
            throw new CollabValidationException(reason);
        }
    }
}
