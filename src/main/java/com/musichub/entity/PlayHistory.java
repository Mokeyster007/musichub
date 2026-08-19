package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("tb_play_history")
public class PlayHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long songId;

    // 新增：播放日期（按天聚合，用于报告统计）
    @TableField("play_date")
    private LocalDate playDate;

    // 新增：当天播放次数（累计）
    @TableField("play_count")
    private Integer playCount;

    // 保留：最后一次播放的精确时间（用于"最近在听"排序）
    private LocalDateTime playTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // PlayHistory.java 中添加
    private Long totalDuration; // 累计播放时长（秒）

}