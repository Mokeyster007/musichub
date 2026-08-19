package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.Song;
import com.musichub.entity.UserFavorite;
import com.musichub.service.ArtistService;
import com.musichub.service.SongService;
import com.musichub.service.UserFavoriteService;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/collect")
public class UserFavoriteController {

    @Autowired
    private UserFavoriteService userFavoriteService;

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    /**
     * 收藏歌曲
     */
    @PostMapping("/add")
    public Result<String> addCollect(@RequestBody UserFavorite favorite) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.error("未登录");
        }

        favorite.setUserId(userId);
        favorite.setType(0);
        favorite.setCreateTime(LocalDateTime.now());

        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("type", 0)
                .eq("song_id", favorite.getSongId());

        long count = userFavoriteService.count(wrapper);
        if (count > 0) {
            return Result.success("你已经收藏过这首歌曲啦！");
        }

        boolean result = userFavoriteService.save(favorite);
        return result ? Result.success("收藏成功！红心点亮！") : Result.error("操作失败！");
    }

    /**
     * 取消收藏歌曲
     */
    @PostMapping("/remove")
    public Result<String> removeCollect(@RequestBody UserFavorite favorite) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.error("未登录");
        }

        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("type", 0)
                .eq("song_id", favorite.getSongId());

        boolean result = userFavoriteService.remove(wrapper);
        return result ? Result.success("取消收藏成功！红心变灰！") : Result.error("操作失败！");
    }

    /**
     * 获取当前用户收藏的歌曲列表
     */
    @GetMapping("/list")
    public Result<?> listFavorites() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.error("未登录");
        }

        QueryWrapper<UserFavorite> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("type", 0);
        
        List<UserFavorite> userFavorites = userFavoriteService.list(wrapper);
        if (userFavorites.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<Long> songIds = userFavorites.stream()
                .map(UserFavorite::getSongId)
                .collect(Collectors.toList());

        List<Song> songs = songService.listByIds(songIds);

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (Song song : songs) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", song.getSongId());
            map.put("songId", song.getSongId());
            map.put("songName", song.getSongName());
            map.put("coverUrl", song.getCoverUrl());
            map.put("songUrl", song.getAudioUrl());
            map.put("duration", song.getDuration());
            map.put("album", song.getAlbum());
            map.put("artistId", song.getArtistId());

            if (song.getArtistId() != null) {
                Artist artist = artistService.getById(song.getArtistId());
                if (artist != null) {
                    map.put("singerName", artist.getArtistName());
                    map.put("artistName", artist.getArtistName());
                } else {
                    map.put("singerName", "未知歌手");
                }
            } else {
                map.put("singerName", "未知歌手");
            }

            map.put("collected", true);
            resultList.add(map);
        }

        return Result.success(resultList);
    }

}
