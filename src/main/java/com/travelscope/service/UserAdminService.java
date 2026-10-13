package com.travelscope.service;

import com.travelscope.entity.User;
import com.travelscope.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户管理服务（FR-A02：列表/禁用/启用/角色）
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository userRepository;
    private final AuthService authService;
    private final PointsService pointsService;
    private final InvitationCodeService invitationCodeService;

    public UserAdminService(UserRepository userRepository, AuthService authService,
                            PointsService pointsService, InvitationCodeService invitationCodeService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.pointsService = pointsService;
        this.invitationCodeService = invitationCodeService;
    }

    /**
     * 全部用户列表（管理端展示）
     */
    public List<User> listAll() {
        return userRepository.findAll();
    }

    /**
     * 禁用用户（status=0 + 删除其全部 token，立即拒绝后续请求）
     */
    public void disable(Long userId) {
        authService.disableUser(userId);
    }

    /**
     * 启用用户（status=1）
     */
    public void enable(Long userId) {
        authService.enableUser(userId);
    }

    /**
     * 修改角色（user ↔ admin）
     */
    public void changeRole(Long userId, String role) {
        if (!"user".equals(role) && !"admin".equals(role)) {
            throw new IllegalArgumentException("非法角色: " + role + "（允许 user / admin）");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        user.setRole(role);
        userRepository.save(user);
        log.info("用户角色已修改: userId={} username={} role={}", userId, user.getUsername(), role);
    }

    /**
     * 调整积分（2026-10-11 积分制度：管理员发放/回收；delta 可负，余额不为负）
     *
     * @return 调整后余额
     */
    public long adjustPoints(Long userId, long delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("调整量不能为 0");
        }
        return pointsService.grant(userId, delta);
    }

    /** 发放邀请码（2026-10-11 注册准入） */
    public com.travelscope.entity.InvitationCode generateInvitationCode(Long adminUserId) {
        return invitationCodeService.generate(adminUserId);
    }

    /** 邀请码列表（管理端） */
    public java.util.List<com.travelscope.entity.InvitationCode> listInvitationCodes() {
        return invitationCodeService.listAll();
    }
}
