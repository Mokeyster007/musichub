package com.musichub.task;

import com.musichub.entity.Song;
import com.musichub.service.SongService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SongPlaySyncTask {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SongService songService;

    // 总榜不动，只同步增量
    private static final String SONG_RANK_DELTA_KEY = "music:song:ranking:delta";

    /**
     * 开发测试：每10秒同步一次
     * 正式环境建议改回 10 分钟：600000
     */
    @Scheduled(fixedRate = 300000, initialDelay = 5000)
    public void syncPlayCountToDatabase() {
        System.out.println("【定时任务】开始将 Redis 增量播放量同步到 MySQL...");

        Set<ZSetOperations.TypedTuple<String>> deltaSet =
                stringRedisTemplate.opsForZSet().rangeWithScores(SONG_RANK_DELTA_KEY, 0, -1);

        if (deltaSet == null || deltaSet.isEmpty()) {
            System.out.println("【定时任务】当前没有增量播放记录需要同步");
            return;
        }

        System.out.println("【定时任务】本次待同步歌曲数量: " + deltaSet.size());

        for (ZSetOperations.TypedTuple<String> tuple : deltaSet) {
            String songIdStr = tuple.getValue();
            Double deltaScore = tuple.getScore();

            if (songIdStr == null || deltaScore == null) {
                continue;
            }

            Long songId = Long.parseLong(songIdStr);
            long delta = deltaScore.longValue();

            Song song = songService.getById(songId);
            if (song == null) {
                // 数据库里没有这首歌，也把脏增量删掉
                stringRedisTemplate.opsForZSet().remove(SONG_RANK_DELTA_KEY, songIdStr);
                continue;
            }

            Long oldCount = song.getPlayCount() == null ? 0L : song.getPlayCount();
            long newCount = oldCount + delta;

            song.setPlayCount(newCount);
            boolean success = songService.updateById(song);

            if (success) {
                // 只有写库成功，才删除这首歌的增量
                stringRedisTemplate.opsForZSet().remove(SONG_RANK_DELTA_KEY, songIdStr);
                System.out.println("✅ 已同步 songId=" + songId
                        + ", 增量=" + delta
                        + ", 数据库新值=" + newCount);
            } else {
                System.out.println("❌ 同步失败 songId=" + songId + ", 增量保留等待下次重试");
            }
        }

        System.out.println("【定时任务】同步完成！");
    }
}