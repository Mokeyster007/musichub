package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.Playlist;
import com.musichub.entity.PlaylistSong;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.PlaylistService;
import com.musichub.service.PlaylistSongService;
import com.musichub.service.SongService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.musichub.utils.UserHolder;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/playlist")
public class PlaylistController {

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistSongService playlistSongService;

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    @Autowired
    private MinioClient minioClient;

    private static final String BUCKET_NAME = "vibe-music-data";

    // =============================================
    // 1. 新建歌单
    // =============================================
    @PostMapping(value = "/create", consumes = "multipart/form-data")
    public Result<?> createPlaylist(
            @RequestParam("title") String title,
            @RequestParam(value = "introduction", required = false) String introduction,
            @RequestParam(value = "cover", required = false) MultipartFile cover
    ) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        if (title == null || title.trim().isEmpty()) return Result.fail("歌单名称不能为空");

        try {
            Playlist playlist = new Playlist();
            playlist.setId(null);
            playlist.setUserId(currentUserId);
            playlist.setTitle(title.trim());
            playlist.setIntroduction(introduction);
            playlist.setCreateTime(new Date());

            String finalCoverUrl = "https://cdn.vuetifyjs.com/images/cards/cooking.png";

            if (cover != null && !cover.isEmpty()) {
                String originalFilename = cover.getOriginalFilename();
                String suffix = (originalFilename != null && originalFilename.contains("."))
                        ? originalFilename.substring(originalFilename.lastIndexOf("."))
                        : ".png";
                String objectName = "playlist/playlist_" + System.currentTimeMillis() + suffix;
                minioClient.putObject(
                        io.minio.PutObjectArgs.builder()
                                .bucket(BUCKET_NAME)
                                .object(objectName)
                                .stream(cover.getInputStream(), cover.getSize(), -1)
                                .contentType(cover.getContentType())
                                .build()
                );
                finalCoverUrl = "http://localhost:9000/" + BUCKET_NAME + "/" + objectName;
            }

            playlist.setCoverUrl(finalCoverUrl);
            boolean success = playlistService.save(playlist);
            return success ? Result.success("歌单创建成功") : Result.fail("创建失败");

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("创建失败：" + e.getMessage());
        }
    }

    // =============================================
    // 2. 我的歌单列表
    // =============================================
    @GetMapping("/my")
    public Result<?> getMyPlaylists() {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        QueryWrapper<Playlist> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", currentUserId).orderByDesc("create_time");
        return Result.success(playlistService.list(wrapper));
    }

    // =============================================
    // 3. 往歌单里加歌（修复：加入歌曲可见性校验）
    // =============================================
    @PostMapping("/addSong")
    public Result<?> addSongToPlaylist(
            @RequestParam("playlistId") String playlistIdStr,
            @RequestParam("songId") String songIdStr
    ) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        Long playlistId = parseId(playlistIdStr);
        Long songId = parseId(songIdStr);
        if (playlistId == null || songId == null) return Result.fail("无效的ID格式");

        // 校验歌单归属
        Playlist playlist = playlistService.getById(playlistId);
        if (playlist == null) return Result.fail("歌单不存在");
        if (!currentUserId.equals(playlist.getUserId())) return Result.fail("无权操作他人的歌单");

        // ↓ 修复新增：校验歌曲是否存在且有权访问
        Song song = songService.getById(songId);
        if (song == null) return Result.fail("歌曲不存在");

        boolean isPublic = song.getStatus() != null && song.getStatus() == 2;
        boolean isOwn = currentUserId.equals(song.getUserId());
        if (!isPublic && !isOwn) return Result.fail("无权添加他人的私人歌曲");

        // 防止重复添加
        QueryWrapper<PlaylistSong> checkWrapper = new QueryWrapper<>();
        checkWrapper.eq("playlist_id", playlistId).eq("song_id", songId);
        if (playlistSongService.count(checkWrapper) > 0) return Result.fail("这首歌已经在该歌单中了");

        PlaylistSong ps = new PlaylistSong();
        ps.setPlaylistId(playlistId);
        ps.setSongId(songId);
        ps.setCreateTime(new Date());

        boolean success = playlistSongService.save(ps);
        return success ? Result.success("已成功加入歌单！") : Result.fail("加入失败");
    }

    // =============================================
    // 4. 查看歌单歌曲列表（修复：批量查歌手，解决N+1问题）
    // =============================================
    @GetMapping("/songs")
    public Result<?> getSongsInPlaylist(@RequestParam("playlistId") String playlistIdStr) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        Long playlistId = parseId(playlistIdStr);
        if (playlistId == null) return Result.fail("无效的播放列表ID格式");

        Playlist playlist = playlistService.getById(playlistId);
        if (playlist == null) return Result.fail("歌单不存在");
        if (!currentUserId.equals(playlist.getUserId())) return Result.fail("无权查看他人的歌单");

        QueryWrapper<PlaylistSong> wrapper = new QueryWrapper<>();
        wrapper.eq("playlist_id", playlistId).orderByDesc("create_time");
        List<PlaylistSong> playlistSongs = playlistSongService.list(wrapper);

        if (playlistSongs.isEmpty()) return Result.success(Collections.emptyList());

        List<Long> songIds = playlistSongs.stream()
                .map(PlaylistSong::getSongId)
                .collect(Collectors.toList());

        List<Song> songs = songService.listByIds(songIds);

        // ↓ 修复：批量查歌手，避免 N+1 查询
        Set<Long> artistIds = songs.stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> artistNameMap = new HashMap<>();
        if (!artistIds.isEmpty()) {
            artistService.listByIds(artistIds).forEach(a ->
                    artistNameMap.put(a.getArtistId(), a.getArtistName()));
        }

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (Song s : songs) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", s.getSongId());
            map.put("songId", s.getSongId());
            map.put("songName", s.getSongName());
            map.put("coverUrl", s.getCoverUrl());
            map.put("songUrl", s.getAudioUrl());
            map.put("audioUrl", s.getAudioUrl());
            map.put("duration", s.getDuration());
            map.put("album", s.getAlbum());
            map.put("artistId", s.getArtistId());
            map.put("singerName", artistNameMap.getOrDefault(s.getArtistId(), "未知歌手"));
            map.put("artistName", artistNameMap.getOrDefault(s.getArtistId(), "未知歌手"));
            resultList.add(map);
        }

        return Result.success(resultList);
    }

    // =============================================
    // 5. 修改歌单封面
    // =============================================
    @PostMapping("/updateCover")
    public Result<String> updatePlaylistCover(
            @RequestParam("playlistId") Long playlistId,
            @RequestParam("file") MultipartFile file
    ) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        try {
            if (file.isEmpty()) return Result.fail("上传文件为空");

            Playlist playlist = playlistService.getById(playlistId);
            if (playlist == null) return Result.fail("歌单不存在");
            if (!currentUserId.equals(playlist.getUserId())) return Result.fail("无权修改他人的歌单");

            String originalFilename = file.getOriginalFilename();
            String suffix = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".png";

            String newFileName = "playlist/playlist_" + playlistId + "_" + System.currentTimeMillis() + suffix;

            minioClient.putObject(
                    io.minio.PutObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(newFileName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );

            String coverUrl = "http://localhost:9000/" + BUCKET_NAME + "/" + newFileName;
            playlist.setCoverUrl(coverUrl);
            boolean updated = playlistService.updateById(playlist);
            return updated ? Result.success(coverUrl) : Result.fail("更新歌单封面失败");

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("上传失败：" + e.getMessage());
        }
    }

    // =============================================
    // 6. 修改歌单信息（名称、简介、风格等）
    // =============================================
    @PostMapping("/update")
    public Result<String> updatePlaylist(@RequestBody Playlist playlist) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        if (playlist.getId() == null) return Result.fail("歌单ID不能为空");
        if (playlist.getTitle() == null) return Result.fail("歌单名称不能为空");

        Playlist dbPlaylist = playlistService.getById(playlist.getId());
        if (dbPlaylist == null) return Result.fail("歌单不存在");
        if (!currentUserId.equals(dbPlaylist.getUserId())) return Result.fail("无权修改他人的歌单");

        dbPlaylist.setTitle(playlist.getTitle());
        dbPlaylist.setIntroduction(playlist.getIntroduction());
        dbPlaylist.setStyle(playlist.getStyle());
        if (playlist.getCoverUrl() != null && !playlist.getCoverUrl().trim().isEmpty()) {
            dbPlaylist.setCoverUrl(playlist.getCoverUrl());
        }

        boolean ok = playlistService.updateById(dbPlaylist);
        return ok ? Result.success("更新成功") : Result.fail("更新失败");
    }

    // =============================================
    // 7. 从歌单移除歌曲（新增）
    // =============================================
    @PostMapping("/removeSong")
    public Result<?> removeSongFromPlaylist(
            @RequestParam("playlistId") String playlistIdStr,
            @RequestParam("songId") String songIdStr
    ) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        Long playlistId = parseId(playlistIdStr);
        Long songId = parseId(songIdStr);
        if (playlistId == null || songId == null) return Result.fail("参数格式错误");

        Playlist playlist = playlistService.getById(playlistId);
        if (playlist == null) return Result.fail("歌单不存在");
        if (!currentUserId.equals(playlist.getUserId())) return Result.fail("无权操作他人歌单");

        QueryWrapper<PlaylistSong> wrapper = new QueryWrapper<>();
        wrapper.eq("playlist_id", playlistId).eq("song_id", songId);

        boolean removed = playlistSongService.remove(wrapper);
        return removed ? Result.success("已从歌单移除") : Result.fail("该歌曲不在歌单中");
    }

    // =============================================
    // 8. 删除歌单（新增，联动清理关联歌曲记录）
    // =============================================
    @PostMapping("/delete")
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deletePlaylist(@RequestParam("playlistId") Long playlistId) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        Playlist playlist = playlistService.getById(playlistId);
        if (playlist == null) return Result.fail("歌单不存在");
        if (!currentUserId.equals(playlist.getUserId())) return Result.fail("无权删除他人歌单");

        // 先清除所有歌曲关联，防止产生孤儿数据
        QueryWrapper<PlaylistSong> psWrapper = new QueryWrapper<>();
        psWrapper.eq("playlist_id", playlistId);
        playlistSongService.remove(psWrapper);

        // 再删歌单本体
        playlistService.removeById(playlistId);
        return Result.success("歌单已删除");
    }

    // =============================================
    // 私有工具方法：解析ID字符串
    // 支持 "apple_536009642" 或纯数字格式
    // =============================================
    private Long parseId(String idStr) {
        try {
            if (idStr == null || idStr.trim().isEmpty()) return null;
            if (idStr.contains("_")) {
                String[] parts = idStr.split("_");
                return Long.parseLong(parts[parts.length - 1]);
            } else {
                return Long.parseLong(idStr);
            }
        } catch (Exception e) {
            System.out.println("ID解析失败：" + idStr);
            return null;
        }
    }
}