package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_comment")
public class Comment implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("song_id")
    private Long songId;

    @TableField("playlist_id")
    private Long playlistId;

    @TableField("content")
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("type")
    private Integer type; // 0代表歌曲评论，1代表歌单评论 (根据你的业务逻辑定义)

    @TableField("like_count")
    private Integer likeCount;

    // ============================================
    // 👇 前端展示需要，但数据库表里没有的字段（连表查询或前端伪造）
    // 使用 @TableField(exist = false) 让 MyBatis 忽略它们，插入时不会报错
    // ============================================
    @TableField(exist = false)
    private String username;

    @TableField(exist = false)
    private String avatar;
}
