package com.musichub.controller;

import com.musichub.entity.Artist;
import com.musichub.entity.PlayHistory;
import com.musichub.entity.Song;
import com.musichub.service.ArtistService;
import com.musichub.service.PlayHistoryService;
import com.musichub.service.SongService;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 听歌报告 Controller
 * 接口前缀：/report
 * period 参数：day / week / month
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    @Autowired
    private PlayHistoryService playHistoryService;

    @Autowired
    private SongService songService;

    @Autowired
    private ArtistService artistService;

    // =====================================================
    // 工具方法：根据 period 计算开始日期
    // =====================================================
    private LocalDate getStartDate(String period) {
        LocalDate today = LocalDate.now();
        return switch (period) {
            case "day"   -> today;
            case "week"  -> today.minusDays(6);
            case "month" -> today.minusDays(29);
            default      -> today.minusDays(6);
        };
    }

    private String getPeriodLabel(String period) {
        return switch (period) {
            case "day"   -> "今天";
            case "week"  -> "本周";
            case "month" -> "本月";
            default      -> "本周";
        };
    }

    // =====================================================
    // 接口 1：综合概览 KPI
    // GET /report/summary?period=week
    // =====================================================
    @GetMapping("/summary")
    public Map<String, Object> getSummary(
            @RequestParam(defaultValue = "week") String period) {

        Long userId = UserHolder.getUserId();
        LocalDate startDate = getStartDate(period);

        // 查该时间段内的所有播放记录
        List<PlayHistory> histories = playHistoryService.lambdaQuery()
                .eq(PlayHistory::getUserId, userId)
                .ge(PlayHistory::getPlayDate, startDate)
                .list();

        // 总播放次数
        int totalPlays = histories.stream()
                .mapToInt(h -> h.getPlayCount() == null ? 0 : h.getPlayCount())
                .sum();

        // 听了几首不同的歌
        long uniqueSongs = histories.stream()
                .map(PlayHistory::getSongId)
                .distinct()
                .count();

        // 听了几位不同的歌手（需要关联歌曲表）
        Set<Long> songIds = histories.stream()
                .map(PlayHistory::getSongId)
                .collect(Collectors.toSet());

        long uniqueArtists = 0;
        long totalSeconds = 0;
        if (!songIds.isEmpty()) {
            List<Song> songs = songService.listByIds(songIds);
            
            // 收集所有歌手ID
            Set<Long> artistIds = songs.stream()
                    .map(Song::getArtistId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            
            // 批量查询歌手信息
            if (!artistIds.isEmpty()) {
                List<Artist> artists = artistService.listByIds(artistIds);
                uniqueArtists = artists.size();
            }
            
            // 构建 songId -> duration 映射
            Map<Long, Integer> durationMap = new HashMap<>();
            for (Song song : songs) {
                if (song.getDuration() != null && !song.getDuration().isEmpty()) {
                    try {
                        // 解析 "3:45" 格式为秒数
                        String[] parts = song.getDuration().split(":");
                        int seconds = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                        durationMap.put(song.getSongId(), seconds);
                    } catch (Exception e) {
                        durationMap.put(song.getSongId(), 0);
                    }
                } else {
                    durationMap.put(song.getSongId(), 0);
                }
            }
            
            // 计算总时长
            for (PlayHistory h : histories) {
                int duration = durationMap.getOrDefault(h.getSongId(), 0);
                int count = h.getPlayCount() == null ? 0 : h.getPlayCount();
                totalSeconds += (long) duration * count;
            }
        }

        // 最活跃时段（从 playTime 的小时提取）
        Map<Integer, Long> hourMap = histories.stream()
                .filter(h -> h.getPlayTime() != null)
                .collect(Collectors.groupingBy(
                        h -> h.getPlayTime().getHour(),
                        Collectors.summingLong(h -> h.getPlayCount() == null ? 0 : h.getPlayCount())
                ));
        int mostActiveHour = hourMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalPlays",     totalPlays);
        result.put("uniqueSongs",    uniqueSongs);
        result.put("uniqueArtists",  uniqueArtists);
        result.put("totalMinutes",   totalSeconds / 60);
        result.put("mostActiveHour", mostActiveHour);
        result.put("periodLabel",    getPeriodLabel(period));
        return result;
    }

    // =====================================================
    // 接口 2：每日播放趋势
    // GET /report/daily-trend?period=week
    // =====================================================
    @GetMapping("/daily-trend")
    public List<Map<String, Object>> getDailyTrend(
            @RequestParam(defaultValue = "week") String period) {

        Long userId = UserHolder.getUserId();
        LocalDate startDate = getStartDate(period);
        LocalDate today = LocalDate.now();

        List<PlayHistory> histories = playHistoryService.lambdaQuery()
                .eq(PlayHistory::getUserId, userId)
                .ge(PlayHistory::getPlayDate, startDate)
                .list();

        // 按日期聚合播放次数
        Map<LocalDate, Integer> dateCountMap = new LinkedHashMap<>();
        // 先把日期区间填满（没数据的天也要补 0，让折线图连续）
        for (LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            dateCountMap.put(d, 0);
        }
        for (PlayHistory h : histories) {
            dateCountMap.merge(h.getPlayDate(), h.getPlayCount() == null ? 0 : h.getPlayCount(), Integer::sum);
        }

        return dateCountMap.entrySet().stream()
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    // 格式化为 MM-dd
                    item.put("date", String.format("%02d-%02d",
                            e.getKey().getMonthValue(), e.getKey().getDayOfMonth()));
                    item.put("playCount", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());
    }

    // =====================================================
    // 接口 3：我的 Top 歌曲排行
    // GET /report/top-songs?period=week&limit=10
    // =====================================================
    @GetMapping("/top-songs")
    public List<Map<String, Object>> getTopSongs(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "10")   int limit) {

        Long userId = UserHolder.getUserId();
        LocalDate startDate = getStartDate(period);

        List<PlayHistory> histories = playHistoryService.lambdaQuery()
                .eq(PlayHistory::getUserId, userId)
                .ge(PlayHistory::getPlayDate, startDate)
                .list();

        // 按 songId 聚合播放次数
        Map<Long, Integer> songCountMap = new HashMap<>();
        for (PlayHistory h : histories) {
            songCountMap.merge(h.getSongId(),
                    h.getPlayCount() == null ? 0 : h.getPlayCount(), Integer::sum);
        }

        // 取 Top N 的 songId
        List<Long> topSongIds = songCountMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (topSongIds.isEmpty()) return Collections.emptyList();

        // 批量查歌曲信息
        Map<Long, Song> songMap = songService.listByIds(topSongIds).stream()
                .collect(Collectors.toMap(Song::getSongId, s -> s));

        // 收集所有歌手ID
        Set<Long> artistIds = songMap.values().stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        // 批量查询歌手名称
        final Map<Long, String> artistNameMap;
        if (!artistIds.isEmpty()) {
            artistNameMap = artistService.listByIds(artistIds).stream()
                    .collect(Collectors.toMap(Artist::getArtistId, Artist::getArtistName));
        } else {
            artistNameMap = Collections.emptyMap();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (Long songId : topSongIds) {
            Song song = songMap.get(songId);
            if (song == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank",        rank++);
            item.put("songId",      songId);
            item.put("songName",    song.getSongName());
            item.put("singerName",  artistNameMap.getOrDefault(song.getArtistId(), "未知歌手"));
            item.put("coverUrl",    song.getCoverUrl());
            item.put("playCount",   songCountMap.get(songId));
            result.add(item);
        }
        return result;
    }

    // =====================================================
    // 接口 4：我的 Top 歌手分布（用于环形图）
    // GET /report/top-artists?period=week&limit=5
    // =====================================================
    @GetMapping("/top-artists")
    public List<Map<String, Object>> getTopArtists(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "5")    int limit) {

        Long userId = UserHolder.getUserId();
        LocalDate startDate = getStartDate(period);

        List<PlayHistory> histories = playHistoryService.lambdaQuery()
                .eq(PlayHistory::getUserId, userId)
                .ge(PlayHistory::getPlayDate, startDate)
                .list();

        if (histories.isEmpty()) return Collections.emptyList();

        Set<Long> songIds = histories.stream()
                .map(PlayHistory::getSongId)
                .collect(Collectors.toSet());
        Map<Long, Song> songMap = songService.listByIds(songIds).stream()
                .collect(Collectors.toMap(Song::getSongId, s -> s));

        // 收集所有歌手ID
        Set<Long> artistIds = songMap.values().stream()
                .map(Song::getArtistId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        // 批量查询歌手名称
        // 批量查询歌手名称
        final Map<Long, String> artistNameMap;
        if (!artistIds.isEmpty()) {
            artistNameMap = artistService.listByIds(artistIds).stream()
                    .collect(Collectors.toMap(Artist::getArtistId, Artist::getArtistName));
        } else {
            artistNameMap = Collections.emptyMap();
        }

        // 按歌手聚合播放次数
        Map<String, Integer> artistCountMap = new HashMap<>();
        for (PlayHistory h : histories) {
            Song song = songMap.get(h.getSongId());
            if (song == null || song.getArtistId() == null) continue;
            String artistName = artistNameMap.get(song.getArtistId());
            if (artistName == null) continue;
            artistCountMap.merge(artistName,
                    h.getPlayCount() == null ? 0 : h.getPlayCount(), Integer::sum);
        }

        int total = artistCountMap.values().stream().mapToInt(Integer::intValue).sum();

        return artistCountMap.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("artistName", e.getKey());
                    item.put("playCount",  e.getValue());
                    item.put("percentage", total == 0 ? 0 :
                            Math.round(e.getValue() * 100.0 / total));
                    return item;
                })
                .collect(Collectors.toList());
    }

    // =====================================================
    // 接口 5：24 小时活跃时段分布
    // GET /report/hourly-distribution?period=week
    // =====================================================
    @GetMapping("/hourly-distribution")
    public List<Map<String, Object>> getHourlyDistribution(
            @RequestParam(defaultValue = "week") String period) {

        Long userId = UserHolder.getUserId();
        LocalDate startDate = getStartDate(period);

        List<PlayHistory> histories = playHistoryService.lambdaQuery()
                .eq(PlayHistory::getUserId, userId)
                .ge(PlayHistory::getPlayDate, startDate)
                .list();

        // 初始化 0~23 小时，全部填 0
        Map<Integer, Integer> hourMap = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            hourMap.put(i, 0);
        }

        for (PlayHistory h : histories) {
            if (h.getPlayTime() == null) continue;
            int hour = h.getPlayTime().getHour();
            hourMap.merge(hour, h.getPlayCount() == null ? 0 : h.getPlayCount(), Integer::sum);
        }

        return hourMap.entrySet().stream()
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("hour",      e.getKey());
                    item.put("playCount", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());
    }
}
