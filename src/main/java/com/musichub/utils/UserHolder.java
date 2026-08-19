package com.musichub.utils;

public class UserHolder {

    // 用 ThreadLocal 保证线程安全
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();

    // ---- userId ----
    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }
    public static Long getUserId() {
        return USER_ID.get();
    }
    public static void removeUserId() {
        USER_ID.remove();
    }

    // ---- role ----
    public static void setRole(String role) {
        USER_ROLE.set(role);
    }
    public static String getRole() {
        return USER_ROLE.get();
    }
    public static void removeRole() {
        USER_ROLE.remove();
    }

    public static boolean isAdmin() {
        String role = USER_ROLE.get();
        // ✅ admin 和 super_admin 都算管理员，都能进后台
        return "admin".equals(role) || "super_admin".equals(role);
    }

    // ✅ 新增：单独判断是否是超级管理员（给 AdminController 用）
    public static boolean isSuperAdmin() {
        return "super_admin".equals(USER_ROLE.get());
    }
}