package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.PlayHistory;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.PlayHistoryService;
import com.musichub.service.SongService;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/asset")
public class UserAssetController {

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    @Autowired
    private PlayHistoryService playHistoryService;

    // =============================================
    // 1. 个人资产总览
    // 前端用来渲染"个人主页"顶部统计数字
    // =============================================
    @GetMapping("/overview")
    public Result<?> getOverview() {
        Long userId = UserHolder.getUserId();
        if (userId == null) return Result.fail("未登录");

        // 我上传的歌曲数（所有状态都算）
        long uploadCount = songService.count(
                new LambdaQueryWrapper<Song>()
                        .eq(Song::getUserId, userId));

        // 私人歌曲数（status=0）
        long privateCount = songService.count(
                new LambdaQueryWrapper<Song>()
                        .eq(Song::getUserId, userId)
                        .eq(Song::getStatus, 0));

        // 待审核歌曲数（status=1）
        long pendingCount = songService.count(
                new LambdaQueryWrapper<Song>()
                        .eq(Song::getUserId, userId)
                        .eq(Song::getStatus, 1));

        // 已发布歌曲数（status=2）
        long publishedCount = songService.count(
                new LambdaQueryWrapper<Song>()
                        .eq(Song::getUserId, userId)
                        .eq(Song::getStatus, 2));

        // 最近播放记录数
        long historyCount = playHistoryService.count(
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getUserId, userId));

        Map<String, Object> data = new HashMap<>();
        data.put("uploadCount", uploadCount);
        data.put("privateCount", privateCount);
        data.put("pendingCount", pendingCount);
        data.put("publishedCount", publishedCount);
        data.put("historyCount", historyCount);

        return Result.success(data);
    }

    // =============================================
    // 2. 我的上传列表
    // 查自己所有上传的歌，不管什么状态，只有自己能看
    // =============================================
    @GetMapping("/my_uploads")
    public Result<?> getMyUploads() {
        Long userId = UserHolder.getUserId();
        if (userId == null) return Result.fail("未登录");

        LambdaQueryWrapper<Song> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Song::getUserId, userId)
                .orderByDesc(Song::getReleaseTime);

        List<Song> songs = songService.list(wrapper);
        if (songs.isEmpty()) return Result.success(Collections.emptyList());

        // 批量查歌手名，避免 N+1 查询
        Set<Long> artistIds = songs.stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> artistNameMap = new HashMap<>();
        if (!artistIds.isEmpty()) {
            List<Artist> artists = artistService.listByIds(artistIds);
            artistNameMap = artists.stream()
                    .collect(Collectors.toMap(Artist::getArtistId, Artist::getArtistName));
        }

        // 组装返回数据，带上 status 字段，前端可以据此渲染状态标签
        List<Map<String, Object>> result = new ArrayList<>();
        for (Song song : songs) {
            Map<String, Object> map = new HashMap<>();
            map.put("songId", song.getSongId());
            map.put("songName", song.getSongName());
            map.put("coverUrl", song.getCoverUrl());
            map.put("audioUrl", song.getAudioUrl());
            map.put("album", song.getAlbum());
            map.put("duration", song.getDuration());
            map.put("status", song.getStatus()); // 0私人 1待审核 2已发布 3已拒绝

            // 把 status 转成中文描述，方便前端直接显示
            String statusLabel;
            switch (song.getStatus() == null ? 0 : song.getStatus()) {
                case 0: statusLabel = "私人"; break;
                case 1: statusLabel = "审核中"; break;
                case 2: statusLabel = "已发布"; break;
                case 3: statusLabel = "已拒绝"; break;
                default: statusLabel = "未知"; break;
            }
            map.put("statusLabel", statusLabel);

            String singerName = artistNameMap.getOrDefault(song.getArtistId(), "未知歌手");
            map.put("singerName", singerName);
            map.put("artistName", singerName);

            result.add(map);
        }

        return Result.success(result);
    }

    // =============================================
    // 3. 申请将私人歌曲发布到平台
    // 用户点击"申请发布"，status: 0 → 1
    // =============================================
    @PostMapping("/song/apply_publish")
    public Result<?> applyPublish(@RequestParam("songId") Long songId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) return Result.fail("未登录");

        Song song = songService.getById(songId);
        if (song == null) return Result.fail("歌曲不存在");

        // 安全校验：只能操作自己的歌
        if (!userId.equals(song.getUserId())) {
            return Result.fail("无权操作他人的歌曲");
        }

        // 只有私人状态(0)或被拒绝(3)的歌，才能申请发布
        if (song.getStatus() != 0 && song.getStatus() != 3) {
            return Result.fail("当前歌曲状态不可申请发布");
        }

        song.setStatus(1); // 改为待审核
        songService.updateById(song);

        return Result.success("已提交发布申请，等待管理员审核");
    }

    // =============================================
    // 4. 取消申请发布（把待审核的歌改回私人）
    // status: 1 → 0
    // =============================================
    @PostMapping("/song/cancel_publish")
    public Result<?> cancelPublish(@RequestParam("songId") Long songId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) return Result.fail("未登录");

        Song song = songService.getById(songId);
        if (song == null) return Result.fail("歌曲不存在");

        if (!userId.equals(song.getUserId())) {
            return Result.fail("无权操作他人的歌曲");
        }

        if (song.getStatus() != 1) {
            return Result.fail("只有审核中的歌曲才能取消申请");
        }

        song.setStatus(0); // 改回私人
        songService.updateById(song);

        return Result.success("已取消发布申请");
    }

    // =============================================
    // 5. 编辑我的歌曲元数据
    // 比如上传时写错了歌名、专辑，可以在这里改
    // =============================================
    @PostMapping("/song/update")
    public Result<?> updateMySong(@RequestBody Song updateData) {
        Long userId = UserHolder.getUserId();
        if (userId == null) return Result.fail("未登录");

        if (updateData.getSongId() == null) return Result.fail("歌曲ID不能为空");

        Song dbSong = songService.getById(updateData.getSongId());
        if (dbSong == null) return Result.fail("歌曲不存在");

        // 安全校验：防止越权修改他人歌曲
        if (!userId.equals(dbSong.getUserId())) {
            return Result.fail("无权修改他人上传的歌曲");
        }

        // 只允许修改这几个字段，其他字段（audioUrl、status）不能通过这里改
        if (updateData.getSongName() != null && !updateData.getSongName().trim().isEmpty()) {
            dbSong.setSongName(updateData.getSongName());
        }
        if (updateData.getAlbum() != null) {
            dbSong.setAlbum(updateData.getAlbum());
        }
        if (updateData.getCoverUrl() != null) {
            dbSong.setCoverUrl(updateData.getCoverUrl());
        }
        if (updateData.getLyric() != null) {
            dbSong.setLyric(updateData.getLyric());
        }

        boolean success = songService.updateById(dbSong);
        return success ? Result.success("修改成功") : Result.fail("修改失败");
    }

    // =============================================
    // 6. 删除我的歌曲（带联动清理，防止脏数据）
    // 删除时同步清理：播放记录
    // =============================================
    @PostMapping("/song/delete")
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deleteMySong(@RequestParam("songId") Long songId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) return Result.fail("未登录");

        Song dbSong = songService.getById(songId);
        if (dbSong == null) return Result.fail("歌曲不存在");

        // 安全校验：只能删除自己上传的歌
        if (!userId.equals(dbSong.getUserId())) {
            return Result.fail("无权删除他人上传的歌曲");
        }

        // 1. 清理最近播放记录
        playHistoryService.remove(
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getSongId, songId));

        // 2. 如果你有歌单关联表（tb_playlist_song），取消注释：
        // playlistSongService.remove(
        //         new LambdaQueryWrapper<PlaylistSong>()
        //                 .eq(PlaylistSong::getSongId, songId));

        // 3. 如果你有收藏表（tb_favorite），取消注释：
        // favoriteService.remove(
        //         new LambdaQueryWrapper<Favorite>()
        //                 .eq(Favorite::getSongId, songId));

        // 4. 最后删除歌曲本体
        songService.removeById(songId);

        return Result.success("删除成功");
    }


}