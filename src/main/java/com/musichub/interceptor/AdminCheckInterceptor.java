package com.musichub.interceptor;

import com.musichub.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminCheckInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // JwtInterceptor 已经保证走到这里的请求一定有 userId 和 role
        // 只需要判断 role 是不是 admin
        if (!UserHolder.isAdmin()) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"权限不足，需要管理员身份\"}");
            return false;
        }
        return true;
    }
}