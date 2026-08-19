package com.musichub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.musichub.entity.Artist;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArtistMapper extends BaseMapper<Artist> {
}
