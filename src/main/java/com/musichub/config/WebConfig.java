package com.musichub.config;

import com.musichub.interceptor.AdminCheckInterceptor;
import com.musichub.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 拦截器配置类，具体拦截URL的，自行添加
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Autowired
    private AdminCheckInterceptor adminCheckInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                // 拦截所有请求 [web:451]
                .addPathPatterns("/**")
                // 排除不需要Token就能访问的接口 [web:448]
                .excludePathPatterns(
                        "/user/login",      // 登录接口不能拦截
                        "/user/register",   // 注册接口也不能拦截
                        "/artist/list",     // 假设这是获取公开歌手列表的接口
                        "/file/upload",
                        "/song/artist/detail",
                        "/song/hot",
                        "/artist/all",
                        "/images/**",
                        "/error",           // Spring Boot 默认的错误处理路径
                        "/swagger-ui/**", "/v3/api-docs/**", // 如果你用了 Swagger
                        "/static/**",
                        "/favicon.ico"
                );

        // ② Admin 拦截器：只拦截 /admin/** 路径
        //    顺序在后（JWT 验证完了才轮到权限验证）
        registry.addInterceptor(adminCheckInterceptor)
                .addPathPatterns("/admin/**")
                .order(2);
    }
}
