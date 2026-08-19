package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.Song;
import com.musichub.mapper.SongMapper;
import com.musichub.service.SongService;
import org.springframework.stereotype.Service;
//新建一个包 com.musichub.service.impl，创建实现类 SongServiceImpl。用来实现mapper中的SongService接口
@Service
public class SongServiceImpl extends ServiceImpl<SongMapper, Song> implements SongService {
}
