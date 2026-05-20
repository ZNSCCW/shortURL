package com.shorturl.repository;

import com.shorturl.model.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 管理员数据访问层。
 */
@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    /** 根据用户名查找管理员 */
    Optional<AdminUser> findByUsername(String username);

    /** 检查用户名是否已存在 */
    boolean existsByUsername(String username);

    /** 统计启用状态下的指定角色管理员数量（用于防止删除最后一个超级管理员） */
    long countByRoleAndEnabledTrue(String role);
}