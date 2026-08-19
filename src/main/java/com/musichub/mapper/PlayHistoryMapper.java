package com.musichub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.musichub.entity.PlayHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlayHistoryMapper extends BaseMapper<PlayHistory> {
}