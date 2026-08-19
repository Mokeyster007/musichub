package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.SongService;
import com.musichub.utils.UserHolder;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/song")
public class AdminSongController {

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    @Autowired
    private MinioClient minioClient;

    private static final String BUCKET_NAME = "vibe-music-data";

    private boolean isAdmin() {
        String role = UserHolder.getRole();
        return "admin".equals(role) || "super_admin".equals(role);
    }

    // ================================
    // 待审核歌曲列表（带歌手名）
    // GET /admin/song/audit
    // ================================
    @GetMapping("/audit")
    public Result<?> getAuditSongs() {
        if (!isAdmin()) return Result.fail("无权限");

        LambdaQueryWrapper<Song> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Song::getStatus, Song.STATUS_AUDITING)
                .orderByAsc(Song::getSongId);

        List<Song> songs = songService.list(wrapper);
        if (songs.isEmpty()) return Result.success(Collections.emptyList());

        return Result.success(buildSongList(songs));
    }

    // ================================
    // 歌曲完整详情（审核用）
    // GET /admin/song/detail/{id}
    // ================================
    @GetMapping("/detail/{id}")
    public Result<?> getSongDetail(@PathVariable Long id) {
        if (!isAdmin()) return Result.fail("无权限");

        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");

        String singerName = "未知歌手";
        if (song.getArtistId() != null) {
            Artist artist = artistService.getById(song.getArtistId());
            if (artist != null) singerName = artist.getArtistName();
        }

        Map<String, Object> map = new HashMap<>();
        map.put("songId", song.getSongId());
        map.put("songName", song.getSongName());
        map.put("coverUrl", song.getCoverUrl());
        map.put("audioUrl", song.getAudioUrl());
        map.put("album", song.getAlbum());
        map.put("duration", song.getDuration());
        map.put("lyric", song.getLyric());
        map.put("status", song.getStatus());
        map.put("userId", song.getUserId());
        map.put("singerName", singerName);
        map.put("releaseTime", song.getReleaseTime());
        map.put("playCount", song.getPlayCount());
        map.put("rejectReason", song.getRejectReason());

        return Result.success(map);
    }

    // ================================
    // 审核通过（清空驳回原因）
    // PUT /admin/song/{id}/approve
    // ================================
    @PutMapping("/{id}/approve")
    public Result<?> approveSong(@PathVariable Long id) {
        if (!isAdmin()) return Result.fail("无权限");

        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (song.getStatus() == null || song.getStatus() != Song.STATUS_AUDITING) {
            return Result.fail("该歌曲不在待审核状态");
        }

        LambdaUpdateWrapper<Song> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Song::getSongId, id)
                .set(Song::getStatus, Song.STATUS_PUBLIC)
                .set(Song::getRejectReason, null);
        songService.update(updateWrapper);

        System.out.println("✅ 审核通过：songId=" + id);
        return Result.success("审核通过，歌曲已发布到平台");
    }

    // ================================
    // 审核拒绝（保存驳回原因）
    // PUT /admin/song/{id}/reject?reason=xxx
    // ================================
    @PutMapping("/{id}/reject")
    public Result<?> rejectSong(
            @PathVariable Long id,
            @RequestParam(value = "reason", required = false, defaultValue = "内容不符合平台规范") String reason
    ) {
        if (!isAdmin()) return Result.fail("无权限");

        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (song.getStatus() == null || song.getStatus() != Song.STATUS_AUDITING) {
            return Result.fail("该歌曲不在待审核状态");
        }

        LambdaUpdateWrapper<Song> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Song::getSongId, id)
                .set(Song::getStatus, Song.STATUS_REJECTED)
                .set(Song::getRejectReason, reason);
        songService.update(updateWrapper);

        System.out.println("❌ 审核拒绝：songId=" + id + "，原因：" + reason);
        return Result.success("已拒绝，歌曲退回用户私人空间");
    }

    // ================================
    // 强制下架已发布歌曲
    // PUT /admin/song/{id}/takedown
    // ================================
    @PutMapping("/{id}/takedown")
    public Result<?> takedownSong(
            @PathVariable Long id,
            @RequestParam(value = "reason", required = false, defaultValue = "违规内容") String reason
    ) {
        if (!isAdmin()) return Result.fail("无权限");

        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (song.getStatus() == null || song.getStatus() != Song.STATUS_PUBLIC) {
            return Result.fail("该歌曲未在平台公开状态");
        }

        LambdaUpdateWrapper<Song> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Song::getSongId, id)
                .set(Song::getStatus, Song.STATUS_PRIVATE)
                .set(Song::getRejectReason, null);
        songService.update(updateWrapper);

        System.out.println("⚠️ 强制下架：songId=" + id + "，原因：" + reason);
        return Result.success("已下架，歌曲退回用户私人库");
    }

    // ================================
    // 平台已发布歌曲列表
    // GET /admin/song/published
    // ================================
    @GetMapping("/published")
    public Result<?> getPublishedList() {
        if (!isAdmin()) return Result.fail("无权限");

        LambdaQueryWrapper<Song> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Song::getStatus, Song.STATUS_PUBLIC)
                .orderByDesc(Song::getReleaseTime);

        List<Song> songs = songService.list(wrapper);
        return Result.success(buildSongList(songs));
    }

    // ================================
    // 管理员直接发布平台歌曲
    // POST /admin/song/publish
    // ================================
    @PostMapping("/publish")
    public Result<?> adminPublishSong(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "singer", required = false) String singer,
            @RequestParam(value = "album", required = false) String album,
            @RequestParam(value = "pic", required = false) String pic,
            @RequestParam(value = "lyric", required = false) String lyric,
            @RequestParam(value = "duration", required = false) String duration
    ) {
        if (!isAdmin()) return Result.fail("无权限");

        if (file.isEmpty() || name == null || name.trim().isEmpty()) {
            return Result.fail("音频文件和歌曲名称不能为空");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String suffix = (originalFilename != null && originalFilename.contains("."))
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".mp3";
            String audioObjectName = "songs/" + UUID.randomUUID() + suffix;

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(audioObjectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            String finalAudioUrl = "http://localhost:9000/" + BUCKET_NAME + "/" + audioObjectName;

            String finalCoverUrl = pic;
            if (pic != null && pic.startsWith("data:image")) {
                String[] parts = pic.split(",");
                byte[] imageBytes = Base64.getDecoder().decode(parts[1]);
                String picSuffix = pic.contains("png") ? ".png" : ".jpg";
                String picObjectName = "songCovers/" + UUID.randomUUID() + picSuffix;
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(BUCKET_NAME)
                                .object(picObjectName)
                                .stream(new ByteArrayInputStream(imageBytes), imageBytes.length, -1)
                                .contentType(pic.contains("png") ? "image/png" : "image/jpeg")
                                .build()
                );
                finalCoverUrl = "http://localhost:9000/" + BUCKET_NAME + "/" + picObjectName;
            }

            Long targetArtistId = null;
            if (singer != null && !singer.trim().isEmpty()) {
                LambdaQueryWrapper<Artist> artistWrapper = new LambdaQueryWrapper<>();
                artistWrapper.eq(Artist::getArtistName, singer.trim());
                Artist existArtist = artistService.getOne(artistWrapper);
                if (existArtist != null) {
                    targetArtistId = existArtist.getArtistId();
                } else {
                    Artist newArtist = new Artist();
                    newArtist.setArtistName(singer.trim());
                    newArtist.setAvatar(finalCoverUrl);
                    artistService.save(newArtist);
                    targetArtistId = newArtist.getArtistId();
                }
            }

            Song newSong = new Song();
            newSong.setSongName(name.trim());
            newSong.setUserId(UserHolder.getUserId());
            newSong.setStatus(Song.STATUS_PUBLIC); // 管理员直接公开
            if (targetArtistId != null) newSong.setArtistId(targetArtistId);
            newSong.setAlbum(album);
            newSong.setAudioUrl(finalAudioUrl);
            newSong.setCoverUrl(finalCoverUrl);
            if (duration != null) newSong.setDuration(duration);
            newSong.setReleaseTime(LocalDate.now());
            newSong.setLyric(lyric);
            newSong.setPlayCount(0L);

            songService.save(newSong);
            return Result.success("发布成功，歌曲已公开到平台");

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("发布失败：" + e.getMessage());
        }
    }

    // ================================
    // 私有工具方法：批量组装歌曲列表（带歌手名，避免 N+1）
    // ================================
    private List<Map<String, Object>> buildSongList(List<Song> songs) {
        if (songs == null || songs.isEmpty()) return Collections.emptyList();

        Set<Long> artistIds = songs.stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> artistNameMap = new HashMap<>();
        if (!artistIds.isEmpty()) {
            artistService.listByIds(artistIds).forEach(a ->
                    artistNameMap.put(a.getArtistId(), a.getArtistName()));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Song song : songs) {
            Map<String, Object> map = new HashMap<>();
            map.put("songId", song.getSongId());
            map.put("songName", song.getSongName());
            map.put("coverUrl", song.getCoverUrl());
            map.put("audioUrl", song.getAudioUrl());
            map.put("album", song.getAlbum());
            map.put("duration", song.getDuration());
            map.put("status", song.getStatus());
            map.put("userId", song.getUserId());
            map.put("singerName", artistNameMap.getOrDefault(song.getArtistId(), "未知歌手"));
            map.put("rejectReason", song.getRejectReason());
            result.add(map);
        }
        return result;
    }
}