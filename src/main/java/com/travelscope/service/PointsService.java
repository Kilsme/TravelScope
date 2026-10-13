package com.travelscope.service;

import com.travelscope.config.AppProperties;
import com.travelscope.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分制度服务（2026-10-11：对话按意图计费，调用前预扣）
 * <p>
 * 扣减是原子 SQL（{@link UserRepository#deductPoints} 单 UPDATE 完成「余额充足校验 +
 * 扣减」）——单用户并发 2 的并发轮次不会双双通过余额校验导致超支。
 * v1 不退款：失败轮次的成本已在模型网关真实发生，积分照扣（退款策略列为后续演进）。
 * </p>
 */
@Service
public class PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsService.class);

    private final UserRepository userRepository;
    private final AppProperties appProperties;

    public PointsService(UserRepository userRepository, AppProperties appProperties) {
        this.userRepository = userRepository;
        this.appProperties = appProperties;
    }

    /**
     * 尝试预扣积分（调用前额度判断）
     *
     * @return true=额度充足已扣减；false=余额不足（或用户不存在），本轮不应执行
     */
    @Transactional
    public boolean tryDeduct(Long userId, int cost) {
        int updated = userRepository.deductPoints(userId, cost);
        if (updated == 0) {
            log.info("积分不足拒绝: userId={}, 需={}", userId, cost);
            return false;
        }
        log.info("积分预扣: userId={}, 扣={}, 剩余={}", userId, cost, balanceOf(userId));
        return true;
    }

    /**
     * 管理员发放/回收积分（delta 可负；回收扣到 0 止，余额不为负）
     *
     * @return 调整后余额
     */
    @Transactional
    public long grant(Long userId, long delta) {
        int updated = userRepository.adjustPoints(userId, delta);
        if (updated == 0) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        long balance = balanceOf(userId);
        log.info("积分调整: userId={}, delta={}, 调整后余额={}", userId, delta, balance);
        return balance;
    }

    /** 查询当前余额 */
    public long balanceOf(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getPointsBalance() == null ? 0L : u.getPointsBalance())
                .orElse(0L);
    }

    /** 按意图取本轮费用（意图分类失败 null 兜底按普通档） */
    public int costOf(boolean planningIntent) {
        return planningIntent
                ? appProperties.getPoints().getPlanningCost()
                : appProperties.getPoints().getChatCost();
    }
}
