package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@Accessors(chain = true)
@TableName(value = "tb_song", autoResultMap = true)

public class Song implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long songId;

    @TableField("artist_id")
    private Long artistId;

    @TableField("name")
    private String songName;

    @TableField("album")
    private String album;

    @TableField("lyric")
    private String lyric;

    @TableField("duration")
    private String duration;

    @TableField("style")
    private String style;

    @TableField("cover_url")
    private String coverUrl;

    @TableField("audio_url")
    private String audioUrl;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField("release_time")
    private LocalDate releaseTime;

    @TableField("play_count")
    private Long playCount;

    @TableField("user_id")
    private Long userId;

    @TableField("status")
    private Integer status;

    @TableField("reject_reason")
    private String rejectReason;

    public static final int STATUS_PRIVATE = 0;
    public static final int STATUS_AUDITING = 1;
    public static final int STATUS_PUBLIC = 2;
    public static final int STATUS_REJECTED = 3;
}