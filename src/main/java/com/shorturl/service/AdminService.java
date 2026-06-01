package com.shorturl.service;

import com.shorturl.model.AdminUser;
import com.shorturl.repository.AdminUserRepository;
import com.shorturl.util.PasswordUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 管理员业务逻辑。
 * 负责初始化默认超级管理员、登录验证、管理员 CRUD 及密码修改。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final AdminUserRepository adminUserRepository;

    public AdminService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    /**
     * 启动时初始化默认超级管理员。
     * 密码必须通过环境变量 DEFAULT_ADMIN_PASSWORD 设置，不允许硬编码默认值。
     */
    @PostConstruct
    @Transactional
    public void initDefaultAdmin() {
        if (!adminUserRepository.existsByUsername("admin")) {
            String defaultPassword = System.getenv("DEFAULT_ADMIN_PASSWORD");
            if (defaultPassword == null || defaultPassword.isBlank()) {
                log.error("============================================================");
                log.error("  致命错误：未设置 DEFAULT_ADMIN_PASSWORD 环境变量");
                log.error("  请先设置环境变量后重新启动，例如：");
                log.error("    export DEFAULT_ADMIN_PASSWORD='你的强密码'");
                log.error("  或创建 .env 文件（参考 .env.example）");
                log.error("============================================================");
                throw new IllegalStateException(
                        "DEFAULT_ADMIN_PASSWORD 环境变量未设置。请设置后重新启动。"
                );
            }
            validatePasswordStrength(defaultPassword);
            AdminUser admin = new AdminUser("admin", PasswordUtil.hash(defaultPassword), "SUPER_ADMIN");
            adminUserRepository.save(admin);

            log.info("============================================================");
            log.info("  超级管理员已创建");
            log.info("  用户名: admin");
            log.info("  密码已通过 DEFAULT_ADMIN_PASSWORD 环境变量注入");
            log.info("============================================================");
        }
    }

    /**
     * 管理员登录验证，返回用户信息（不含密码）。
     * 使用恒定时间验证策略防止用户枚举：当用户不存在时也会执行一次 BCrypt 哈希比较，
     * 使得"用户存在但密码错误"和"用户不存在"两种情况的耗时趋于一致。
     */
    public Optional<AdminUser> authenticate(String username, String rawPassword) {
        Optional<AdminUser> user = adminUserRepository.findByUsername(username);
        if (user.isPresent()) {
            AdminUser u = user.get();
            if (u.isEnabled() && PasswordUtil.matches(rawPassword, u.getPasswordHash())) {
                return Optional.of(u);
            }
            return Optional.empty();
        }
        // 用户不存在时，使用一个"不可能匹配"的 BCrypt hash 消耗时间
        // 消除用户枚举的时序差异
        PasswordUtil.matches(rawPassword,
                "$2a$10$XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        return Optional.empty();
    }

    /**
     * 注册新管理员（仅超级管理员可调用）
     */
    @Transactional
    public AdminUser createAdmin(String username, String rawPassword, String role) {
        if (adminUserRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }
        validatePasswordStrength(rawPassword);
        AdminUser user = new AdminUser(username, PasswordUtil.hash(rawPassword), role);
        return adminUserRepository.save(user);
    }

    /**
     * 删除管理员（不允许删除最后一个超级管理员）
     */
    @Transactional
    public boolean deleteAdmin(Long id) {
        var opt = adminUserRepository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        AdminUser target = opt.get();
        // 防止删除最后一个超级管理员
        if ("SUPER_ADMIN".equals(target.getRole())) {
            long superAdminCount = adminUserRepository.countByRoleAndEnabledTrue("SUPER_ADMIN");
            if (superAdminCount <= 1) {
                throw new RuntimeException("不允许删除最后一个超级管理员");
            }
        }
        adminUserRepository.delete(target);
        return true;
    }

    /**
     * 禁用/启用管理员
     */
    @Transactional
    public boolean toggleEnabled(Long id) {
        Optional<AdminUser> opt = adminUserRepository.findById(id);
        if (opt.isPresent()) {
            AdminUser user = opt.get();
            user.setEnabled(!user.isEnabled());
            adminUserRepository.save(user);
            return true;
        }
        return false;
    }

    /**
     * 修改密码
     */
    @Transactional
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        validatePasswordStrength(newPassword);
        Optional<AdminUser> opt = adminUserRepository.findByUsername(username);
        if (opt.isPresent()) {
            AdminUser user = opt.get();
            if (PasswordUtil.matches(oldPassword, user.getPasswordHash())) {
                user.setPasswordHash(PasswordUtil.hash(newPassword));
                adminUserRepository.save(user);
                return true;
            }
        }
        return false;
    }

    /**
     * 密码强度校验：长度至少 8 位，必须同时包含字母和数字。
     */
    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new RuntimeException("密码长度至少 8 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
            if (hasLetter && hasDigit) break;
        }
        if (!hasLetter || !hasDigit) {
            throw new RuntimeException("密码必须同时包含字母和数字");
        }
    }

    public List<AdminUser> getAllAdmins() {
        return adminUserRepository.findAll();
    }
}