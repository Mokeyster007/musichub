package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("tb_playlist")
public class Playlist {
    @TableId(type = IdType.AUTO)
    private Integer id;

    private Long userId;
    private String title;
    private String coverUrl;
    private String introduction;
    private String style;
    private Date createTime;
}
