package com.musichub.interceptor;

import com.musichub.entity.User;
import com.musichub.service.UserService;
import com.musichub.utils.JwtUtil;
import com.musichub.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String token = request.getHeader("Authorization");

        // 1. 没有 token → 直接 401
        if (token == null || token.trim().isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或 Token 已过期\"}");
            return false;
        }

        try {
            // 2. 解析 token
            Long userId = JwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"Token 无效\"}");
                return false;
            }

            // 3. 查用户（顺便把 role 一起存进 UserHolder）
            User user = userService.getById(userId);
            if (user == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"用户不存在\"}");
                return false;
            }

            // ✅ 新增：用户不存在
            if (user == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":401,\"message\":\"用户不存在\"}"
                );
                return false;
            }

            // ✅ 把之前的 status 判断改成 isBanned
            if (user.getIsBanned() != null && user.getIsBanned() == 1) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":403,\"message\":\"账号已被封禁，请联系管理员\"}"
                );
                return false;
            }

            // 4. 把 userId 和 role 都存入 UserHolder
            UserHolder.setUserId(userId);
            UserHolder.setRole(user.getRole());  // ← 新增，后面 AdminCheck 用

            return true;

        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Token 解析失败\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束后清除，防止内存泄漏
        UserHolder.removeUserId();
        UserHolder.removeRole();  // ← 新增
    }
}