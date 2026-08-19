package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.Artist;
import com.musichub.mapper.ArtistMapper;
import com.musichub.service.ArtistService;
import org.springframework.stereotype.Service;

@Service
public class ArtistServiceImpl extends ServiceImpl<ArtistMapper, Artist> implements ArtistService {
}
