package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.SongService;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/search")
public class SearchController {

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    /**
     * 全局搜索
     * 返回三类结果：songs / artists / albums（轻量聚合版）
     *
     * 核心安全规则：
     * - 未登录用户：只能搜到 status=2（平台公开）的歌
     * - 已登录用户：能搜到 status=2（平台公开）+ 自己上传的所有歌（任意status）
     */
    @GetMapping
    public Result<?> globalSearch(@RequestParam("keyword") String keyword) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.fail("关键词不能为空");
        }

        String kw = keyword.trim();
        Long currentUserId = UserHolder.getUserId();

        // 1. 查名字匹配的歌手
        LambdaQueryWrapper<Artist> artistWrapper = new LambdaQueryWrapper<>();
        artistWrapper.like(Artist::getArtistName, kw);
        List<Artist> directArtists = artistService.list(artistWrapper);

        List<Long> matchedArtistIds = directArtists.stream()
                .map(Artist::getArtistId)
                .collect(Collectors.toList());

        // 使用 Map 保存所有涉及到的完整歌手对象，以 artistId 为 key 进行去重
        Map<Long, Artist> allRelatedArtistsMap = new HashMap<>();
        directArtists.forEach(a -> allRelatedArtistsMap.put(a.getArtistId(), a));

        // 2. 查歌曲
        LambdaQueryWrapper<Song> songWrapper = new LambdaQueryWrapper<>();
        addVisibilityFilter(songWrapper, currentUserId);

        songWrapper.and(w -> {
            w.like(Song::getSongName, kw)
                    .or()
                    .like(Song::getAlbum, kw);
            if (!matchedArtistIds.isEmpty()) {
                w.or(x -> x.in(Song::getArtistId, matchedArtistIds));
            }
        });

        List<Song> songs = songService.list(songWrapper);

        // 找出所有歌曲涉及到的，但还没查出完整信息的额外歌手 ID
        Set<Long> extraArtistIds = songs.stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .filter(id -> !allRelatedArtistsMap.containsKey(id))
                .collect(Collectors.toSet());

        // 查出这些额外的歌手，并加入去重 Map
        if (!extraArtistIds.isEmpty()) {
            artistService.listByIds(extraArtistIds).forEach(a ->
                    allRelatedArtistsMap.put(a.getArtistId(), a));
        }

        // 组装歌曲列表返回数据
        List<Map<String, Object>> songList = new ArrayList<>();
        for (Song song : songs) {
            Map<String, Object> map = new HashMap<>();
            map.put("songId", song.getSongId());
            map.put("id", song.getSongId()); // 前端可能需要通用的 id 字段
            map.put("songName", song.getSongName());
            map.put("name", song.getSongName()); // 前端可能需要通用的 name 字段
            map.put("coverUrl", song.getCoverUrl());
            map.put("audioUrl", song.getAudioUrl());
            map.put("album", song.getAlbum());
            map.put("duration", song.getDuration());
            map.put("status", song.getStatus());
            map.put("isOwn", currentUserId != null && currentUserId.equals(song.getUserId()));

            // 从去重 Map 里取歌手名字
            Artist artist = allRelatedArtistsMap.get(song.getArtistId());
            String singerName = (artist != null) ? artist.getArtistName() : "未知歌手";
            map.put("singerName", singerName);
            map.put("artistName", singerName);
            songList.add(map);
        }

        // 3. 组装歌手列表返回数据（解决多个重复和前端字段不匹配问题）
        List<Map<String, Object>> artistList = new ArrayList<>();
        // 遍历已经去重的 Map
        for (Artist artist : allRelatedArtistsMap.values()) {
            Map<String, Object> map = new HashMap<>();
            map.put("artistId", artist.getArtistId());
            map.put("id", artist.getArtistId()); // 强制加上通用的 id 字段给前端路由跳转用
            map.put("artistName", artist.getArtistName());
            map.put("name", artist.getArtistName()); // 强制加上通用的 name 字段
            map.put("avatar", artist.getAvatar());   // 请确保这和你实体类里的头像字段名一致，如果叫 pic 请改成 getPic()
            map.put("gender", artist.getGender());
            artistList.add(map);
        }

        // 4. 聚合专辑
        LambdaQueryWrapper<Song> albumWrapper = new LambdaQueryWrapper<>();
        addVisibilityFilter(albumWrapper, currentUserId);
        albumWrapper.like(Song::getAlbum, kw).isNotNull(Song::getAlbum);

        List<Song> albumSongs = songService.list(albumWrapper).stream()
                .filter(s -> s.getAlbum() != null && !s.getAlbum().trim().isEmpty())
                .collect(Collectors.toList());

        Map<String, List<Song>> albumGroupMap = albumSongs.stream()
                .collect(Collectors.groupingBy(Song::getAlbum));

        List<Map<String, Object>> albumList = new ArrayList<>();
        for (Map.Entry<String, List<Song>> entry : albumGroupMap.entrySet()) {
            List<Song> group = entry.getValue();
            Map<String, Object> map = new HashMap<>();
            map.put("albumName", entry.getKey());
            map.put("songCount", group.size());
            map.put("coverUrl", group.get(0).getCoverUrl());

            Artist artist = allRelatedArtistsMap.get(group.get(0).getArtistId());
            map.put("singerName", (artist != null) ? artist.getArtistName() : "未知歌手");
            albumList.add(map);
        }

        // 5. 组装最终结果
        Map<String, Object> result = new HashMap<>();
        result.put("songs", songList);
        result.put("artists", artistList); // 返回组装好的 map 列表，而不是原始实体
        result.put("albums", albumList);
        result.put("total", songList.size() + artistList.size() + albumList.size());

        return Result.success(result);
    }

    /**
     * 核心方法：给查询条件加上数据可见性漏斗
     *
     * 规则：
     * - 未登录（currentUserId == null）：只能看 status=2 的公开歌
     * - 已登录（currentUserId != null）：能看 status=2 的公开歌 + 自己上传的所有歌
     *
     * 这个方法抽出来，是因为热歌榜、最近播放等接口也可能需要用
     */
    private void addVisibilityFilter(LambdaQueryWrapper<Song> wrapper, Long currentUserId) {
        if (currentUserId == null) {
            // 未登录：只能看公开歌
            wrapper.eq(Song::getStatus, 2);
        } else {
            // 已登录：公开歌 OR 自己上传的歌
            wrapper.and(w -> w
                    .eq(Song::getStatus, 2)
                    .or()
                    .eq(Song::getUserId, currentUserId)
            );
        }
    }
}