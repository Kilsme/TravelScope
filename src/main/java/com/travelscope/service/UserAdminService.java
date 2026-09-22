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

    public UserAdminService(UserRepository userRepository, AuthService authService) {
        this.userRepository = userRepository;
        this.authService = authService;
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
}
