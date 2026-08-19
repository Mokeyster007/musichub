package com.musichub.dto; // 根据你的包名调整

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CommentDTO {
    // --- 评论表 (comment) 的字段 ---
    private Long id;
    private Long userId;
    private Long songId;
    private String content;
    private Integer likeCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    // --- 用户表 (user) 的字段 (联表查出来的) ---
    private String username; // 用户昵称
    private String avatar;   // 用户头像地址 (如果你的 user 表里叫 pic，这里可以改名叫 pic)
}
