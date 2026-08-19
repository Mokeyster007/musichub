package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.Playlist;
import com.musichub.mapper.PlaylistMapper;
import com.musichub.service.PlaylistService;
import org.springframework.stereotype.Service;

@Service
public class PlaylistServiceImpl extends ServiceImpl<PlaylistMapper, Playlist> implements PlaylistService {
}
