package com.musichub.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;

public class JwtUtil {

    // 1. 定义一个安全的字符串密钥（为了满足 HS256，最好长一点）
    private static final String SECRET_STRING = "MusicHubSecretKeyForMyAwesomeProjectNeverShareAndItMustBeLongEnough123";

    // 2. 将字符串转换为符合 JWT 规范的 SecretKey 对象
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STRING.getBytes());

    // 3. 定义 Token 的过期时间（24 小时）
    private static final long EXPIRATION_TIME = 24 * 60 * 60 * 1000;

    /**
     * 功能 1：给登录成功的用户生成一个 Token
     */
    public static String generateToken(Long userId) {
        return Jwts.builder()
                // 把 userId 塞进 Token 里作为主体 (Subject)
                .setSubject(String.valueOf(userId))
                // 签发时间（现在）
                .setIssuedAt(new Date())
                // 过期时间（现在 + 24 小时）
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                // 用规范的 Key 对象进行签名加密
                .signWith(SECRET_KEY, SignatureAlgorithm.HS256)
                // 压缩打包成字符串
                .compact();
    }

    /**
     * 功能 2：从前端传来的 Token 中解密出用户 ID
     */
    public static Long getUserIdFromToken(String token) {
        try {
            // 用规范的 Key 对象去解密
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(SECRET_KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // 如果解密成功，把里面藏着的 userId 拿出来
            String userIdStr = claims.getSubject();
            return Long.parseLong(userIdStr);

        } catch (Exception e) {
            // Token 伪造的、或者过期了，返回 null
            return null;
        }
    }
}
