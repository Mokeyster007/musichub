package com.musichub.dto;

import lombok.Data;


@Data
// 登录请求的 DTO，用来存储前端输入的账号密码给到控制器
public class LoginRequest {
    private String username;
    private String password;
}
