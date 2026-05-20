package com.shorturl.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 密码工具类。
 * 提供密码哈希和验证方法，使用 Spring Security 的 BCryptPasswordEncoder。
 */
public class PasswordUtil {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 对明文密码进行 BCrypt 哈希。
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希字符串
     */
    public static String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 验证明文密码与哈希是否匹配。
     *
     * @param rawPassword    明文密码
     * @param hashedPassword BCrypt 哈希
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String hashedPassword) {
        return encoder.matches(rawPassword, hashedPassword);
    }
}
