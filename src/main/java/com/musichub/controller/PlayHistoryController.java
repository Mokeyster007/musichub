package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.musichub.common.Result;
import com.musichub.entity.Artist;
import com.musichub.entity.PlayHistory;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.PlayHistoryService;
import com.musichub.service.SongService;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/history")
public class PlayHistoryController {

    @Autowired
    private PlayHistoryService playHistoryService;

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    /**
     * 获取最近播放记录（按最后播放时间倒序，合并展示）
     */
    @GetMapping("/recent")
    public Result<?> getRecentPlayHistory() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        LambdaQueryWrapper<PlayHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlayHistory::getUserId, userId)
                .orderByDesc(PlayHistory::getPlayTime);

        List<PlayHistory> rawHistoryList = playHistoryService.list(wrapper);

        // 按 songId 去重，只保留最新的一条记录
        List<PlayHistory> historyList = new ArrayList<>();
        Set<Long> seenSongIds = new HashSet<>();
        for (PlayHistory h : rawHistoryList) {
            if (!seenSongIds.contains(h.getSongId())) {
                historyList.add(h);
                seenSongIds.add(h.getSongId());
            }
            if (historyList.size() >= 20) break; // 只取最近 20 首
        }

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (PlayHistory history : historyList) {
            Song song = songService.getById(history.getSongId());
            if (song == null) continue;

            String singerName = "未知歌手";
            if (song.getArtistId() != null) {
                Artist artist = artistService.getById(song.getArtistId());
                if (artist != null) {
                    singerName = artist.getArtistName();
                }
            }

            Map<String, Object> map = new HashMap<>();
            map.put("id", song.getSongId());
            map.put("songId", song.getSongId());
            map.put("name", song.getSongName());
            map.put("songName", song.getSongName());
            map.put("coverUrl", song.getCoverUrl());
            map.put("audioUrl", song.getAudioUrl());
            map.put("url", song.getAudioUrl());
            map.put("singerName", singerName);
            map.put("artistName", singerName);
            map.put("playTime", history.getPlayTime());

            resultList.add(map);
        }

        return Result.success(resultList);
    }

    /**
     * 获取用户听歌全局统计（包含各周期的时长、播放次数、歌曲数、歌手数、活跃时段）
     */
    @GetMapping("/stats")
    public Result<?> getPlayDurationStats() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.with(DayOfWeek.MONDAY);
        LocalDate startOfMonth = today.withDayOfMonth(1);

        // 分别计算三个周期的完整 KPI
        Map<String, Object> dailyStats = calculatePeriodStats(userId, today);
        Map<String, Object> weeklyStats = calculatePeriodStats(userId, startOfWeek);
        Map<String, Object> monthlyStats = calculatePeriodStats(userId, startOfMonth);

        Map<String, Object> result = new HashMap<>();
        // 兼容旧版前端直接取 duration 的需求
        result.put("daily", dailyStats.get("duration"));
        result.put("weekly", weeklyStats.get("duration"));
        result.put("monthly", monthlyStats.get("duration"));

        // 返回包含 4 个核心 KPI 的新对象，供前端全面展示
        result.put("dailyStats", dailyStats);
        result.put("weeklyStats", weeklyStats);
        result.put("monthlyStats", monthlyStats);

        return Result.success(result);
    }

    /**
     * 核心聚合方法：计算指定日期以来的所有 KPI 指标
     */
    private Map<String, Object> calculatePeriodStats(Long userId, LocalDate startDate) {
        Map<String, Object> stats = new HashMap<>();

        // 1. 查询时长、播放次数、去重歌曲数
        QueryWrapper<PlayHistory> wrapper = new QueryWrapper<>();
        wrapper.select(
                        "IFNULL(SUM(total_duration), 0) as sumDuration",
                        "IFNULL(SUM(play_count), 0) as sumPlayCount",
                        "COUNT(DISTINCT song_id) as uniqueSongCount"
                )
                .eq("user_id", userId)
                .ge("play_date", startDate);

        Map<String, Object> map = playHistoryService.getMap(wrapper);

        long duration = 0L;
        long playCount = 0L;
        long songCount = 0L;

        if (map != null) {
            duration = map.get("sumDuration") != null ? Long.parseLong(map.get("sumDuration").toString()) : 0L;
            playCount = map.get("sumPlayCount") != null ? Long.parseLong(map.get("sumPlayCount").toString()) : 0L;
            songCount = map.get("uniqueSongCount") != null ? Long.parseLong(map.get("uniqueSongCount").toString()) : 0L;
        }

        // 2. 纯 Java 内存计算去重歌手数 (免写 Mapper)
        QueryWrapper<PlayHistory> songIdWrapper = new QueryWrapper<>();
        songIdWrapper.select("DISTINCT song_id").eq("user_id", userId).ge("play_date", startDate);
        List<PlayHistory> historySongs = playHistoryService.list(songIdWrapper);

        long artistCount = 0L;
        if (historySongs != null && !historySongs.isEmpty()) {
            List<Long> songIds = historySongs.stream().map(PlayHistory::getSongId).collect(Collectors.toList());
            if (!songIds.isEmpty()) {
                LambdaQueryWrapper<Song> songWrapper = new LambdaQueryWrapper<>();
                songWrapper.in(Song::getSongId, songIds).isNotNull(Song::getArtistId);
                artistCount = songService.list(songWrapper).stream()
                        .map(Song::getArtistId)
                        .distinct()
                        .count();
            }
        }

        // 3. 纯 Java 计算最活跃时段 (出现最多次数的小时)
        QueryWrapper<PlayHistory> timeWrapper = new QueryWrapper<>();
        timeWrapper.select("play_time").eq("user_id", userId).ge("play_date", startDate).isNotNull("play_time");
        List<PlayHistory> timeRecords = playHistoryService.list(timeWrapper);

        int activeHour = 0;
        if (timeRecords != null && !timeRecords.isEmpty()) {
            Map<Integer, Long> hourCounts = timeRecords.stream()
                    .filter(h -> h.getPlayTime() != null)
                    .collect(Collectors.groupingBy(h -> h.getPlayTime().getHour(), Collectors.counting()));
            if (!hourCounts.isEmpty()) {
                activeHour = Collections.max(hourCounts.entrySet(), Map.Entry.comparingByValue()).getKey();
            }
        }

        stats.put("duration", duration);
        stats.put("playCount", playCount);
        stats.put("songCount", songCount);
        stats.put("artistCount", artistCount);
        stats.put("activeHour", activeHour);

        return stats;
    }

    /**
     * 统一上报接口
     */
    @PostMapping("/report")
    public Result<?> reportPlayHistory(@RequestBody Map<String, Object> params) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return Result.fail("未登录");
        }

        Object songIdObj = params.get("songId");
        if (songIdObj == null) {
            return Result.fail("songId不能为空");
        }

        Long songId;
        Long duration = 0L;
        try {
            songId = Long.valueOf(songIdObj.toString());
            Object durationObj = params.get("duration");
            if (durationObj != null) {
                duration = Long.valueOf(durationObj.toString());
            }
        } catch (Exception e) {
            return Result.fail("参数格式错误");
        }

        LocalDate today = LocalDate.now();

        LambdaQueryWrapper<PlayHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlayHistory::getUserId, userId)
                .eq(PlayHistory::getSongId, songId)
                .eq(PlayHistory::getPlayDate, today)
                .orderByDesc(PlayHistory::getPlayTime);

        List<PlayHistory> list = playHistoryService.list(wrapper);

        if (list != null && !list.isEmpty()) {
            PlayHistory main = list.get(0);

            long currentDuration = main.getTotalDuration() == null ? 0L : main.getTotalDuration();
            int currentCount = main.getPlayCount() == null ? 0 : main.getPlayCount();

            main.setTotalDuration(currentDuration + duration);
            main.setPlayCount(currentCount + 1);
            main.setPlayTime(LocalDateTime.now());
            playHistoryService.updateById(main);

            if (list.size() > 1) {
                List<Long> duplicateIds = list.stream()
                        .skip(1)
                        .map(PlayHistory::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                if (!duplicateIds.isEmpty()) {
                    playHistoryService.removeByIds(duplicateIds);
                }
            }
        } else {
            PlayHistory history = new PlayHistory();
            history.setUserId(userId);
            history.setSongId(songId);
            history.setTotalDuration(duration);
            history.setPlayCount(1);
            history.setPlayTime(LocalDateTime.now());
            history.setPlayDate(today);
            playHistoryService.save(history);
        }

        return Result.success("上报成功");
    }
}