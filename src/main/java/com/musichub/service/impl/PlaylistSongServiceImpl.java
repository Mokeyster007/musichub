package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.PlaylistSong;
import com.musichub.mapper.PlaylistSongMapper;
import com.musichub.service.PlaylistSongService;
import org.springframework.stereotype.Service;

@Service
public class PlaylistSongServiceImpl extends ServiceImpl<PlaylistSongMapper, PlaylistSong> implements PlaylistSongService {
}
