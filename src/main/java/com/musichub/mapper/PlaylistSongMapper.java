package com.musichub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.musichub.entity.PlaylistSong;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlaylistSongMapper extends BaseMapper<PlaylistSong> {
}
