package com.musichub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("tb_playlist_song")
public class PlaylistSong {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long playlistId;
    private Long songId;
    private Date createTime;
}
