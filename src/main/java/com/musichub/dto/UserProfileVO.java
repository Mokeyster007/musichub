package com.musichub.dto;

import lombok.Data;

@Data
public class UserProfileVO {
    private Long id;
    private String username; // 使用 username 替代 nickname
    private String email;
    private String phone;
    private String avatar;
    private String signature;
    private String bgCover;
    private String role;

    // 页面上的数据统计
    private Integer uploadCount;   // 上传歌曲数量
    private Integer playlistCount; // 创建的歌单数量
    private Integer favoriteCount; // 喜欢的歌曲数量
}