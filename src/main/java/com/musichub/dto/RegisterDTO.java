package com.musichub.dto;

import lombok.Data;

// ✅ 新增 RegisterDTO.java
@Data
public class RegisterDTO {
    private String username;
    private String password;
    private String email;
    private String phone;
}