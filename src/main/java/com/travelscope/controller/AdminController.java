package com.travelscope.controller;

import com.travelscope.dto.TokenUsageVO;
import com.travelscope.entity.User;
import com.travelscope.security.AuthInterceptor;
import com.travelscope.service.EvaluationRecordService;
import com.travelscope.service.TokenUsageService;
import com.travelscope.service.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员接口：用户管理 + Token 用量报表（FR-A01 / FR-A02）
 * <p>
 * 全部要求 role=admin（AuthInterceptor 已注入 userRole，Controller 统一校验；
 * 非 admin → 403）。/admin/** 命中现有 Vite /api 代理无需改前端代理。
 * </p>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserAdminService userAdminService;
    private final TokenUsageService tokenUsageService;
    private final com.travelscope.service.LoadShedService loadShedService;
    private final EvaluationRecordService evaluationRecordService;

    public AdminController(UserAdminService userAdminService, TokenUsageService tokenUsageService,
                           com.travelscope.service.LoadShedService loadShedService,
                           EvaluationRecordService evaluationRecordService) {
        this.userAdminService = userAdminService;
        this.tokenUsageService = tokenUsageService;
        this.loadShedService = loadShedService;
        this.evaluationRecordService = evaluationRecordService;
    }

    // ==================== 过载状态（2026-09-23 并发改造） ====================

    /**
     * 过载降级状态查询（GREEN/YELLOW/RED + 原因 + 网关剩余并发）——运维观测接口
     */
    @GetMapping("/load-status")
    public ResponseEntity<Map<String, Object>> loadStatus(HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return ResponseEntity.status(forbidden.getStatusCode()).build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("level", loadShedService.getLevel().name());
        body.put("reason", loadShedService.getLastReason());
        return ResponseEntity.ok(body);
    }

    // ==================== 用户管理（FR-A02） ====================

    /**
     * 用户列表（role/status/时间）
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        List<User> users = userAdminService.listAll();
        return ResponseEntity.ok(users.stream().map(this::toVO).toList());
    }

    /**
     * 禁用用户（status=0 + 清除全部 token）
     */
    @PutMapping("/users/{id}/disable")
    public ResponseEntity<?> disableUser(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        userAdminService.disable(id);
        return ResponseEntity.ok(Map.of("message", "用户已禁用", "userId", id));
    }

    /**
     * 启用用户
     */
    @PutMapping("/users/{id}/enable")
    public ResponseEntity<?> enableUser(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        userAdminService.enable(id);
        return ResponseEntity.ok(Map.of("message", "用户已启用", "userId", id));
    }

    /**
     * 修改角色（user / admin）
     */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> changeRole(@PathVariable Long id,
                                        @RequestParam String role,
                                        HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        userAdminService.changeRole(id, role);
        return ResponseEntity.ok(Map.of("message", "角色已修改", "userId", id, "role", role));
    }

    // ==================== 积分制度（2026-10-11） ====================

    /**
     * 调整用户积分（发放/回收；delta 可负，回收扣到 0 止）
     */
    @PutMapping("/users/{id}/points")
    public ResponseEntity<?> adjustPoints(@PathVariable Long id,
                                          @RequestParam long delta,
                                          HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        try {
            long balance = userAdminService.adjustPoints(id, delta);
            return ResponseEntity.ok(Map.of("message", "积分已调整", "userId", id,
                    "delta", delta, "balance", balance));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", 400, "message", e.getMessage()));
        }
    }

    // ==================== 邀请码（2026-10-11 注册准入） ====================

    /**
     * 发放邀请码（生成随机 8 位码）
     */
    @PostMapping("/invitation-codes")
    public ResponseEntity<?> generateInvitationCode(HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        User admin = (User) request.getAttribute(AuthInterceptor.ATTR_CURRENT_USER);
        var ic = userAdminService.generateInvitationCode(admin.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", ic.getId(), "code", ic.getCode(), "createdAt", ic.getCreatedAt()));
    }

    /**
     * 邀请码列表（新→旧：码/使用人/使用时间）
     */
    @GetMapping("/invitation-codes")
    public ResponseEntity<?> listInvitationCodes(HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        return ResponseEntity.ok(userAdminService.listInvitationCodes().stream().map(ic -> {
            Map<String, Object> vo = new HashMap<String, Object>();
            vo.put("id", ic.getId());
            vo.put("code", ic.getCode());
            vo.put("createdBy", ic.getCreatedBy());
            vo.put("usedBy", ic.getUsedBy());
            vo.put("usedAt", ic.getUsedAt());
            vo.put("createdAt", ic.getCreatedAt());
            return vo;
        }).toList());
    }

    // ==================== Token 用量（FR-A01） ====================

    /**
     * Token 用量报表：按用户/按天/按模型聚合
     *
     * @param userId   可选用户过滤
     * @param dateFrom 可选起始日期（yyyy-MM-dd）
     * @param dateTo   可选结束日期（yyyy-MM-dd）
     */
    @GetMapping("/token-usage")
    public ResponseEntity<?> tokenUsage(@RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String dateFrom,
                                        @RequestParam(required = false) String dateTo,
                                        HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        LocalDate from = parseDate(dateFrom);
        LocalDate to = parseDate(dateTo);
        List<TokenUsageVO> rows = tokenUsageService.query(userId, from, to);
        return ResponseEntity.ok(rows);
    }

    // ==================== 评测看板（2026-10-14 P2-M） ====================

    /**
     * 评测汇总：总数/通过率/坏例数 + 按类别、按用例两个分组聚合
     */
    @GetMapping("/evaluation/summary")
    public ResponseEntity<?> evaluationSummary(HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        return ResponseEntity.ok(evaluationRecordService.summary());
    }

    /**
     * 评测记录列表（坏例诊断用，含双轨原始 JSON）
     *
     * @param caseId      可选按用例过滤（如 EC-001）
     * @param category    可选按类别过滤（如 MULTI_CITY）
     * @param badCaseOnly true 只看坏例
     * @param limit       条数上限（默认 50）
     */
    @GetMapping("/evaluation/records")
    public ResponseEntity<?> evaluationRecords(@RequestParam(required = false) String caseId,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(defaultValue = "false") boolean badCaseOnly,
                                               @RequestParam(defaultValue = "50") int limit,
                                               HttpServletRequest request) {
        ResponseEntity<?> forbidden = requireAdmin(request);
        if (forbidden != null) {
            return forbidden;
        }
        return ResponseEntity.ok(evaluationRecordService.records(caseId, category, badCaseOnly, limit));
    }

    // ==================== 内部 ====================

    /**
     * 要求 role=admin；非 admin 返回 403，admin 返回 null（通过）
     */
    private ResponseEntity<?> requireAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ROLE);
        if (!"admin".equals(role)) {
            Map<String, Object> body = new HashMap<>();
            body.put("code", HttpStatus.FORBIDDEN.value());
            body.put("message", "需要管理员权限");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        return null;
    }

    private Map<String, Object> toVO(User u) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", u.getId());
        vo.put("username", u.getUsername());
        vo.put("nickname", u.getNickname());
        vo.put("role", u.getRole());
        vo.put("status", u.getStatus());
        vo.put("pointsBalance", u.getPointsBalance());
        vo.put("createdAt", u.getCreatedAt());
        vo.put("updatedAt", u.getUpdatedAt());
        return vo;
    }

    private static LocalDate parseDate(String s) {
        return (s == null || s.isBlank()) ? null : LocalDate.parse(s);
    }
}
