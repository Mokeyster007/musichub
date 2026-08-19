package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.User;
import com.musichub.mapper.UserMapper;
import com.musichub.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
