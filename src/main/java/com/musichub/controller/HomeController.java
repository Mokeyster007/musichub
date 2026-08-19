package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.SongService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/home")
public class HomeController {

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    @GetMapping("/index")
    public Result<?> getHomeData() {
        Map<String, Object> homeData = new HashMap<>();

        try {
            // ✅ 1. 轮播图：播放量最高的 3 首【只取公开歌曲】
            LambdaQueryWrapper<Song> bannerWrapper = new LambdaQueryWrapper<>();
            bannerWrapper.eq(Song::getStatus, Song.STATUS_PUBLIC)
                    .orderByDesc(Song::getPlayCount)
                    .last("LIMIT 3");
            List<Song> bannerSongs = songService.list(bannerWrapper);

            // ✅ 2. 最新入库：按 songId 降序取最新 8 首【只取公开歌曲】
            LambdaQueryWrapper<Song> newWrapper = new LambdaQueryWrapper<>();
            newWrapper.eq(Song::getStatus, Song.STATUS_PUBLIC)
                    .orderByDesc(Song::getSongId)
                    .last("LIMIT 8");
            List<Song> newSongs = songService.list(newWrapper);

            // ✅ 3. 随机漫游：随机 6 首【只取公开歌曲】
            LambdaQueryWrapper<Song> randomWrapper = new LambdaQueryWrapper<>();
            randomWrapper.eq(Song::getStatus, Song.STATUS_PUBLIC)
                    .last("ORDER BY RAND() LIMIT 6");
            List<Song> randomSongs = songService.list(randomWrapper);

            // 合并所有歌曲，批量查询歌手（消灭 N+1）
            List<Song> allSongs = new ArrayList<>();
            allSongs.addAll(bannerSongs);
            allSongs.addAll(newSongs);
            allSongs.addAll(randomSongs);
            Map<Long, String> artistNameMap = buildArtistNameMap(allSongs);

            homeData.put("banners", convertToDtoList(bannerSongs, artistNameMap));
            homeData.put("newSongs", convertToDtoList(newSongs, artistNameMap));
            homeData.put("randomSongs", convertToDtoList(randomSongs, artistNameMap));

            return Result.success(homeData);

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("获取首页数据失败: " + e.getMessage());
        }
    }

    /**
     * 批量查歌手名，避免 N+1 查询
     * 原来每首歌都单独 getById 一次，10首歌就是10次查询
     * 现在合并成1次 listByIds，性能大幅提升
     */
    private Map<Long, String> buildArtistNameMap(List<Song> songs) {
        Set<Long> artistIds = songs.stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (artistIds.isEmpty()) return Collections.emptyMap();

        return artistService.listByIds(artistIds).stream()
                .collect(Collectors.toMap(
                        Artist::getArtistId,
                        Artist::getArtistName,
                        (a, b) -> a
                ));
    }

    /**
     * Song 列表 → DTO Map 列表（使用预先批量查好的歌手名 Map）
     */
    private List<Map<String, Object>> convertToDtoList(
            List<Song> songList,
            Map<Long, String> artistNameMap) {

        return songList.stream().map(song -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", song.getSongId());
            map.put("songId", song.getSongId());
            map.put("name", song.getSongName());
            map.put("songName", song.getSongName());
            map.put("coverUrl", song.getCoverUrl());
            map.put("audioUrl", song.getAudioUrl());
            map.put("playCount", song.getPlayCount());
            map.put("singerName", artistNameMap.getOrDefault(song.getArtistId(), "未知歌手"));
            return map;
        }).collect(Collectors.toList());
    }
}