package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import java.io.Serializable;

@Data
@Accessors(chain = true)
@TableName(value = "tb_artist", autoResultMap = true)
public class Artist implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long artistId;

    @TableField("name")
    private String artistName;

    @TableField("avatar")
    private String avatar; // 歌手头像（以前叫 pic）

    @TableField("introduction")
    private String introduction; // 歌手简介（以前叫 introduction）

    @TableField("gender")
    private Integer gender; // 0女 1男 2组合等

    @TableField("area")
    private String area; // 地区（以前叫 location）

    @TableField("birth")
    private String birth; // 地区（以前叫 location）

    @TableField("style")
    private String style;
}
