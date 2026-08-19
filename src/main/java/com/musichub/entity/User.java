package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_user")
public class User {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;


    private String username;

    private String password;

    private String email;
    
    private String phone; // 用户手机号

    @TableField("user_avatar")
    private String avatar;// 用户头像地址

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("status")
    private Integer status;

    @TableField("signature")
    private String signature; // 个性签名

    @TableField("bg_cover")
    private String bgCover; // 主页背景图

    @TableField("role")
    private String role;

    @TableField("is_banned")
    private Integer isBanned; // 0=正常，1=封禁

}