package com.musichub.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.Song;
import com.musichub.entity.UserFavorite;
import com.musichub.mapper.UserFavoriteMapper;
import com.musichub.service.SongService;
import com.musichub.service.UserFavoriteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
//创建一个CollectServiceImpl类继承ServiceImpl类，并实现CollectService接口，用于具体实现存放用户收藏的歌曲的功能
public class UserFavoriteServiceImpl extends ServiceImpl<UserFavoriteMapper, UserFavorite> implements UserFavoriteService {

    @Autowired
    private SongService songService;
    @Override
    public List<Song> listFavoriteSongsByUser(Long userId) {
        // 1. 查用户收藏的歌曲 ID
        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).eq("type", 0); // type=0 是歌曲
        List<UserFavorite> favorites = this.list(wrapper);

        // 2. 提取 songId 列表
        List<Long> songIds = favorites.stream()
                .map(UserFavorite::getSongId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (songIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 批量查歌曲详情
        return songService.listByIds(songIds);
    }

}
