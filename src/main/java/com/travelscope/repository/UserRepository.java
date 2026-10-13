package com.travelscope.repository;

import com.travelscope.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /**
     * 原子扣减积分（2026-10-11 积分制度）：单条 UPDATE 完成「余额充足校验 + 扣减」——
     * 并发轮次（单用户并发 2）不会双双通过余额校验导致超支。
     *
     * @return 影响行数：1=扣减成功；0=用户不存在或余额不足
     */
    @Modifying
    @Query("UPDATE User u SET u.pointsBalance = u.pointsBalance - :cost "
            + "WHERE u.id = :userId AND u.pointsBalance >= :cost")
    int deductPoints(@Param("userId") Long userId, @Param("cost") long cost);

    /**
     * 原子调整积分（管理员发放/回收）：回收时余额不为负（不足则扣到 0 止）。
     * delta 可正可负。
     */
    @Modifying
    @Query("UPDATE User u SET u.pointsBalance = CASE WHEN u.pointsBalance + :delta < 0 THEN 0 "
            + "ELSE u.pointsBalance + :delta END WHERE u.id = :userId")
    int adjustPoints(@Param("userId") Long userId, @Param("delta") long delta);
}
