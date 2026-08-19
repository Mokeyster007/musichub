package com.musichub.dto;

import lombok.Data;

@Data
public class UserUpdateDTO {
    // 允许用户修改的字段
    private String username;
    private String email;
    private String phone;
    private String signature;
    private String avatar;
    private String bgCover;
}