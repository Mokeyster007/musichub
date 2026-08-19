package com.musichub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.musichub.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}