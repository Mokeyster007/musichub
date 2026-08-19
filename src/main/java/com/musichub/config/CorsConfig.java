package com.musichub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // ✅ 允许前端地址（开发环境）
        config.addAllowedOrigin("http://localhost:5173");
        config.addAllowedOrigin("http://localhost:3000");
        config.addAllowedOrigin("http://127.0.0.1:5173");

        // ✅ 允许所有请求头
        config.addAllowedHeader("*");

        // ✅ 允许所有方法（GET/POST/PUT/DELETE/OPTIONS）
        config.addAllowedMethod("*");

        // ✅ 允许携带 Cookie 和 Authorization 头
        config.setAllowCredentials(false); // 你用的是 token 不是 cookie，false 即可

        // ✅ 预检请求缓存 1 小时，减少 OPTIONS 请求次数
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}