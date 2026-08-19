package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.musichub.common.Result;
import com.musichub.dto.SongParseDTO;
import com.musichub.entity.Artist;
import com.musichub.entity.PlayHistory;
import com.musichub.entity.Song;
import com.musichub.service.LrclibService;
import com.musichub.service.NeteaseScraperService;
import com.musichub.service.PlayHistoryService;
import com.musichub.service.SongService;
import com.musichub.service.ArtistService;
import com.musichub.utils.MusicScraperUtil;
import com.musichub.utils.UserHolder;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/song")
public class SongController {

    private static final Logger log = LoggerFactory.getLogger(SongController.class);

    @Autowired
    private SongService songService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private LrclibService lrclibService;

    @Autowired
    private NeteaseScraperService neteaseScraperService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ArtistService artistService;

    @Autowired
    private PlayHistoryService playHistoryService;

    @Autowired
    private RestTemplate restTemplate;

    private static final String BUCKET_NAME = "vibe-music-data";

    /**
     * ✅ 从配置文件读取 MinIO endpoint，避免硬编码 localhost:9000
     * application.yml 里配置：minio.endpoint=http://localhost:9000
     */
    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    // Redis 榜单 Key
    /** 总榜：热歌榜 / 排行榜查询使用 */
    private static final String SONG_RANK_TOTAL_KEY = "music:song:ranking:total";
    /** 增量榜：定时任务刷库使用 */
    private static final String SONG_RANK_DELTA_KEY = "music:song:ranking:delta";

    // ================================================================
    // 播放记录（已废弃，统一使用 /history/report 接口）
    // ================================================================

    // ================================================================
    // 最近播放
    // ================================================================

    /**
     * 获取当前登录用户最近播放记录（按最后播放时间倒序，最多 20 条）
     */
    @GetMapping("/history/recent")
    public Result<?> getRecentPlayed() {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        QueryWrapper<PlayHistory> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", currentUserId)
                .orderByDesc("play_time")
                .last("LIMIT 20");

        List<PlayHistory> historyList = playHistoryService.list(wrapper);
        if (historyList.isEmpty()) return Result.success(Collections.emptyList());

        List<Long> songIds = historyList.stream().map(PlayHistory::getSongId).collect(Collectors.toList());
        Map<Long, Song> songMap = songService.listByIds(songIds).stream()
                .collect(Collectors.toMap(Song::getSongId, s -> s));

        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayHistory history : historyList) {
            Song song = songMap.get(history.getSongId());
            if (song == null) continue;
            String singerName = resolveSingerName(song.getArtistId());
            Map<String, Object> map = new HashMap<>();
            map.put("songId",     song.getSongId());
            map.put("songName",   song.getSongName());
            map.put("coverUrl",   song.getCoverUrl());
            map.put("audioUrl",   song.getAudioUrl());
            map.put("singerName", singerName);
            map.put("duration",   song.getDuration());
            map.put("playTime",   history.getPlayTime());
            result.add(map);
        }
        return Result.success(result);
    }

    // ================================================================
    // 热歌榜 / Top10
    // ================================================================

    /** 热歌榜（Top10）*/
    @GetMapping("/hot")
    public Result<?> getHotSongs() { return Result.success(buildTopSongList(10)); }

    /** Top10 榜单 */
    @GetMapping("/top10")
    public Result<?> getTop10Songs() { return Result.success(buildTopSongList(10)); }

    /**
     * 从 Redis 总榜中组装排行榜数据
     * 只展示 status=2（公开）的歌曲
     */
    private List<Map<String, Object>> buildTopSongList(int limit) {
        Set<ZSetOperations.TypedTuple<String>> topSongs =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(SONG_RANK_TOTAL_KEY, 0, limit - 1);

        List<Map<String, Object>> resultList = new ArrayList<>();
        if (topSongs == null || topSongs.isEmpty()) return resultList;

        for (ZSetOperations.TypedTuple<String> tuple : topSongs) {
            String songIdStr = tuple.getValue();
            if (songIdStr == null) continue;
            Song song = songService.getById(Long.parseLong(songIdStr));
            if (song == null || song.getStatus() == null || song.getStatus() != 2) continue;

            String singerName = resolveSingerName(song.getArtistId());
            Map<String, Object> map = new HashMap<>();
            map.put("songId",     song.getSongId());
            map.put("id",         song.getSongId());
            map.put("songName",   song.getSongName());
            map.put("name",       song.getSongName());
            map.put("coverUrl",   song.getCoverUrl());
            map.put("audioUrl",   song.getAudioUrl());
            map.put("url",        song.getAudioUrl());
            map.put("album",      song.getAlbum());
            map.put("duration",   song.getDuration());
            map.put("singerName", singerName);
            map.put("artistName", singerName);
            map.put("playCount",  tuple.getScore() != null ? tuple.getScore().longValue() : 0);
            resultList.add(map);
        }
        return resultList;
    }

    // ================================================================
    // 歌手歌曲列表 / 歌曲详情
    // ================================================================

    /**
     * 按歌手 ID 获取歌曲列表
     * 未登录用户只能看公开歌曲；登录用户额外能看自己上传的私人歌曲
     */
    @GetMapping("/artist/detail")
    public Result<?> getSongsByArtistId(@RequestParam("artistId") Long artistId) {
        Long currentUserId = UserHolder.getUserId();
        LambdaQueryWrapper<Song> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Song::getArtistId, artistId);
        if (currentUserId == null) {
            queryWrapper.eq(Song::getStatus, 2);
        } else {
            queryWrapper.and(w -> w.eq(Song::getStatus, 2).or().eq(Song::getUserId, currentUserId));
        }
        return Result.success(songService.list(queryWrapper));
    }

    /**
     * 获取歌曲详情（播放时调用）
     * 功能：
     *   1. 权限校验（私人歌曲只有本人可访问）
     *   2. 歌词自动补全（网易云 → Lrclib 二级兜底）
     *   3. ✅ 封面自动回填：对封面为空的老数据，刮削后存入 MinIO 并回写数据库
     */
    @GetMapping("/detail")
    public Result<?> getSongDetail(@RequestParam("songId") Long songId) {
        Song song = songService.getById(songId);
        if (song == null) return Result.fail("歌曲不存在");

        // 权限校验：私人歌曲只有上传者本人可访问
        Long currentUserId = UserHolder.getUserId();
        boolean isPublic = song.getStatus() != null && song.getStatus() == 2;
        boolean isOwner  = currentUserId != null && currentUserId.equals(song.getUserId());
        if (!isPublic && !isOwner) return Result.fail("无权访问该歌曲");

        String singerName = resolveSingerName(song.getArtistId());

        // ── 歌词自动补全 ──
        String currentLyric = song.getLyric();
        boolean needsFetch  = currentLyric == null || currentLyric.trim().isEmpty()
                || currentLyric.contains("暂无歌词") || currentLyric.contains("加载失败");
        
        if (needsFetch) {
            log.info("📝 [歌词补全] 检测到需要抓取歌词 | songId={} | 歌曲名={} | 歌手={} | 当前歌词状态={}",
                    song.getSongId(), song.getSongName(), singerName,
                    currentLyric == null ? "null" : "空/占位符");
            
            try {
                log.info("🔍 [歌词补全-网易云] 开始请求...");
                String fetchedLyric = neteaseScraperService.fetchLyricFromNetease(song.getSongName(), singerName);
                
                if (fetchedLyric == null || fetchedLyric.trim().isEmpty()) {
                    log.warn("⚠️ [歌词补全-网易云] 未找到歌词，启动备用引擎 Lrclib...");
                    log.info("🔍 [歌词补全-Lrclib] 开始请求...");
                    fetchedLyric = lrclibService.fetchLyric(song.getSongName(), singerName, song.getDuration());
                    
                    if (fetchedLyric != null && !fetchedLyric.trim().isEmpty()) {
                        log.info("✅ [歌词补全-Lrclib] 命中！歌词长度: {} 字符", fetchedLyric.length());
                    } else {
                        log.warn("❌ [歌词补全-Lrclib] 也未找到歌词");
                    }
                } else {
                    log.info("✅ [歌词补全-网易云] 成功获取！歌词长度: {} 字符", fetchedLyric.length());
                }
                
                String finalLyric = fetchedLyric != null && !fetchedLyric.trim().isEmpty()
                        ? fetchedLyric : "[00:00.00]暂无歌词\n[00:02.00]请您欣赏";
                
                song.setLyric(finalLyric);
                boolean updateSuccess = songService.updateById(song);
                
                if (updateSuccess) {
                    log.info("✅ [歌词补全-数据库] 更新成功 | songId={}", song.getSongId());
                    currentLyric = song.getLyric();
                } else {
                    log.error("❌ [歌词补全-数据库] 更新失败 | songId={}", song.getSongId());
                    currentLyric = finalLyric;
                }
                
            } catch (Exception e) {
                log.error("❌ [歌词补全-异常] 播放时补全歌词失败 | songId={} | 歌曲名={} | 错误类型={} | 错误信息={}",
                        song.getSongId(), song.getSongName(), e.getClass().getSimpleName(), e.getMessage(), e);
                currentLyric = "[00:00.00]歌词加载失败\n[00:02.00]请专心听歌吧";
            }
        } else {
            log.info("ℹ️ [歌词补全] 无需抓取，已有歌词 | songId={} | 歌词长度={}",
                    song.getSongId(), currentLyric == null ? 0 : currentLyric.length());
        }

        // ── ✅ 封面自动回填（针对封面为空的老数据） ──
        if (song.getCoverUrl() == null || song.getCoverUrl().trim().isEmpty()) {
            try {
                // 先尝试网易云刮封面
                String scrapedCover = neteaseScraperService.fetchSongCover(song.getSongName(), singerName);
                // 网易云没有则尝试苹果 iTunes
                if (scrapedCover == null || scrapedCover.trim().isEmpty()) {
                    MusicScraperUtil.ScrapedData scraped =
                            MusicScraperUtil.scrapeFromApple(song.getSongName(), singerName);
                    if (scraped != null) scrapedCover = scraped.getCoverUrl();
                }
                if (scrapedCover != null && !scrapedCover.trim().isEmpty()) {
                    // 下载外链封面并存入 MinIO，保证数据库统一存 MinIO URL
                    String minioUrl = downloadAndUploadCoverToMinio(scrapedCover);
                    if (minioUrl != null) {
                        song.setCoverUrl(minioUrl);
                        songService.updateById(song);
                        log.info("✅ 老数据封面回填 MinIO 成功: {}", minioUrl);
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ 封面回填失败（不影响播放）: {}", e.getMessage());
            }
        }

        Map<String, Object> songInfo = new HashMap<>();
        songInfo.put("id",         song.getSongId());
        songInfo.put("songId",     song.getSongId());
        songInfo.put("songName",   song.getSongName());
        songInfo.put("name",       song.getSongName());
        songInfo.put("coverUrl",   song.getCoverUrl());
        songInfo.put("audioUrl",   song.getAudioUrl());
        songInfo.put("url",        song.getAudioUrl());
        songInfo.put("lyric",      currentLyric);
        songInfo.put("duration",   song.getDuration());
        songInfo.put("album",      song.getAlbum());
        songInfo.put("singerName", singerName);
        songInfo.put("artistName", singerName);
        return Result.success(songInfo);
    }

    // ================================================================
    // 编辑歌曲
    // ================================================================

    /**
     * 编辑歌曲（仅允许编辑 status=0 私人 或 status=-1 被拒 的歌曲）
     * 支持替换音频文件、封面（Base64 或外链均自动存入 MinIO）、歌手、专辑、歌词
     */
    @PutMapping("/{id}/edit")
    public Result<?> editSong(
            @PathVariable Long id,
            @RequestParam(value = "file",     required = false) MultipartFile file,
            @RequestParam("name")                                String name,
            @RequestParam(value = "singer",   required = false) String singer,
            @RequestParam(value = "album",    required = false) String album,
            @RequestParam(value = "pic",      required = false) String pic,
            @RequestParam(value = "lyric",    required = false) String lyric,
            @RequestParam(value = "duration", required = false) String duration
    ) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");

        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (!currentUserId.equals(song.getUserId())) return Result.fail("无权编辑他人歌曲");

        int status = song.getStatus() == null ? -1 : song.getStatus();
        if (status != Song.STATUS_PRIVATE && status != Song.STATUS_REJECTED)
            return Result.fail("只有私人歌曲或被拒歌曲可以编辑");

        if (name == null || name.trim().isEmpty()) return Result.fail("歌曲名称不能为空");

        try {
            // 音频：有新文件才替换
            String finalAudioUrl = song.getAudioUrl();
            if (file != null && !file.isEmpty()) finalAudioUrl = uploadAudioToMinio(file);

            // 封面：有新封面才替换（Base64 / 外链都统一经过 resolveCoverUrl 存 MinIO）
            String finalCoverUrl = song.getCoverUrl();
            if (pic != null && !pic.trim().isEmpty()) finalCoverUrl = resolveCoverUrl(pic);

            // 歌手：有新名字才重新解析 artistId
            Long targetArtistId = song.getArtistId();
            if (singer != null && !singer.trim().isEmpty())
                targetArtistId = resolveArtistId(singer.trim(), finalCoverUrl);

            Song update = new Song();
            update.setSongId(id);
            update.setSongName(name.trim());
            update.setAlbum(album);
            update.setLyric(lyric);
            update.setDuration(duration);
            update.setAudioUrl(finalAudioUrl);
            update.setCoverUrl(finalCoverUrl);
            update.setArtistId(targetArtistId);
            songService.updateById(update);

            Map<String, Object> data = new HashMap<>();
            data.put("songId",   id);
            data.put("status",   song.getStatus());
            data.put("songName", name.trim());
            return Result.success(data);
        } catch (Exception e) {
            log.error("编辑歌曲失败 | songId={}", id, e);
            return Result.fail("编辑失败：" + e.getMessage());
        }
    }

    // ================================================================
    // 解析音频元数据（/parse）
    // ================================================================

    /**
     * 解析上传的音频文件，返回预览数据给前端填充上传表单。
     * 封面优先级（三级兜底，任意一级成功即停止）：
     *   1. 音频文件内嵌 Tag Artwork → Base64，前端直接预览
     *   2. 苹果 iTunes 刮削          → 外链 URL（发布时 publish 接口会存入 MinIO）
     *   3. ✅ 网易云刮削（新增兜底）  → 外链 URL（发布时 publish 接口会存入 MinIO）
     * 注意：parse 阶段不写数据库，封面只是给前端预览用，真正入库在 publish。
     */
    @PostMapping("/parse")
    public Result<?> parseAudioMetadata(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.fail("文件不能为空");

        File tempFile = null;
        try {
            String originalFilename = file.getOriginalFilename();
            String baseFilename = originalFilename != null
                    ? originalFilename.substring(0, originalFilename.lastIndexOf(".")) : "未知歌曲";
            String suffix = originalFilename != null
                    ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".mp3";

            tempFile = File.createTempFile("audio_" + UUID.randomUUID(), suffix);
            file.transferTo(tempFile);

            AudioFile audioFile = AudioFileIO.read(tempFile);
            Tag tag = audioFile.getTag();

            SongParseDTO parseDTO = new SongParseDTO();
            parseDTO.setName(baseFilename);

            // ── 第一步：读取音频文件内嵌 Tag ──
            if (tag != null) {
                if (tag.getFirst(FieldKey.TITLE) != null && !tag.getFirst(FieldKey.TITLE).isEmpty())
                    parseDTO.setName(tag.getFirst(FieldKey.TITLE));
                parseDTO.setSinger(tag.getFirst(FieldKey.ARTIST));
                parseDTO.setAlbum(tag.getFirst(FieldKey.ALBUM));

                // 内嵌封面 → Base64，前端直接渲染预览图，无需网络请求
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    String base64Url = "data:" + artwork.getMimeType() + ";base64,"
                            + Base64.getEncoder().encodeToString(artwork.getBinaryData());
                    parseDTO.setPic(base64Url);
                    log.info("✅ 成功提取本地封面并转为 Base64 预览");
                }
            }

            // ── 第二步：内嵌封面或专辑缺失 → 苹果 iTunes 刮削 ──
            if (parseDTO.getPic() == null || parseDTO.getPic().isEmpty()
                    || parseDTO.getAlbum() == null || parseDTO.getAlbum().isEmpty()) {
                MusicScraperUtil.ScrapedData scrapedData =
                        MusicScraperUtil.scrapeFromApple(parseDTO.getName(), parseDTO.getSinger());
                if (scrapedData != null) {
                    if (parseDTO.getAlbum() == null || parseDTO.getAlbum().isEmpty())
                        parseDTO.setAlbum(scrapedData.getAlbum());
                    if (parseDTO.getPic() == null || parseDTO.getPic().isEmpty()) {
                        parseDTO.setPic(scrapedData.getCoverUrl());
                        log.info("✅ 苹果 iTunes 补全封面: {}", scrapedData.getCoverUrl());
                    }
                }
            }

            // ── ✅ 第三步：苹果也没找到封面 → 网易云刮削兜底 ──
            // 注意：fetchSongCover 需要在 NeteaseScraperService 中实现（见下方说明）
            if (parseDTO.getPic() == null || parseDTO.getPic().isEmpty()) {
                try {
                    String neteaseCover = neteaseScraperService
                            .fetchSongCover(parseDTO.getName(), parseDTO.getSinger());
                    if (neteaseCover != null && !neteaseCover.trim().isEmpty()) {
                        parseDTO.setPic(neteaseCover);
                        log.info("✅ 网易云补全封面: {}", neteaseCover);
                    }
                } catch (Exception e) {
                    // 封面刮削失败不影响解析主流程
                    log.warn("⚠️ 网易云封面刮削失败（不影响上传）: {}", e.getMessage());
                }
            }

            if (parseDTO.getPic() == null || parseDTO.getPic().isEmpty()) {
                log.warn("⚠️ 三级兜底均未找到封面，歌曲：{}", parseDTO.getName());
            }

            // ── 刮歌词：网易云 → Lrclib 二级兜底 ──
            String lrcStr = neteaseScraperService.fetchLyricFromNetease(parseDTO.getName(), parseDTO.getSinger());
            if (lrcStr == null || lrcStr.trim().isEmpty()) {
                log.info("上传解析时网易云未找到歌词，尝试 Lrclib...");
                lrcStr = lrclibService.fetchLyric(parseDTO.getName(), parseDTO.getSinger(), null);
            }
            if (lrcStr != null && !lrcStr.trim().isEmpty()) parseDTO.setLyric(lrcStr);

            parseDTO.setDuration(String.valueOf(audioFile.getAudioHeader().getTrackLength()));
            return Result.success(parseDTO);

        } catch (Exception e) {
            log.error("解析音频文件失败", e);
            return Result.fail("解析音频文件失败：" + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
        }
    }

    // ================================================================
    // 发布歌曲（/publish）
    // ================================================================

    /**
     * 用户上传并保存歌曲到数据库（status=0 私人，等待本人申请审核）
     * 封面处理逻辑（三步，保证数据库统一存 MinIO URL）：
     *   1. Base64   → 解码 → 上传 MinIO
     *   2. 外链 URL → 下载字节流 → 上传 MinIO
     *   3. ✅ 无封面（pic 为空）→ 网易云/苹果刮削 → 下载 → 上传 MinIO
     */
    @PostMapping("/publish")
    public Result<?> publishSong(
            @RequestParam("file")                                MultipartFile file,
            @RequestParam("name")                                String name,
            @RequestParam(value = "singer",   required = false) String singer,
            @RequestParam(value = "album",    required = false) String album,
            @RequestParam(value = "pic",      required = false) String pic,
            @RequestParam(value = "lyric",    required = false) String lyric,
            @RequestParam(value = "duration", required = false) String duration
    ) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        if (file.isEmpty() || name == null || name.trim().isEmpty())
            return Result.fail("音频文件和歌曲名称不能为空");

        try {
            // ── Step 1：上传音频到 MinIO ──
            String originalFilename = file.getOriginalFilename();
            String suffix = (originalFilename != null && originalFilename.contains("."))
                    ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".mp3";
            String audioObjectName = "songs/" + UUID.randomUUID() + suffix;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET_NAME).object(audioObjectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType()).build());
            String finalAudioUrl = minioEndpoint + "/" + BUCKET_NAME + "/" + audioObjectName;
            log.info("✅ 音频上传 MinIO 成功: {}", finalAudioUrl);

            // ── Step 2：处理封面 → 统一落地 MinIO ──
            String finalCoverUrl = null;

            if (pic != null && !pic.trim().isEmpty()) {
                if (pic.startsWith("data:image")) {
                    // 前端传来的 Base64（本地 Tag 内嵌封面）
                    finalCoverUrl = resolveCoverUrl(pic);
                    log.info("✅ Base64 封面上传 MinIO 成功: {}", finalCoverUrl);
                } else if (pic.startsWith("http")) {
                    // 前端传来的外链（苹果 iTunes 或网易云刮削封面）→ 下载 → 存 MinIO
                    finalCoverUrl = downloadAndUploadCoverToMinio(pic);
                    log.info("✅ 外链封面下载并上传 MinIO 成功: {}", finalCoverUrl);
                }
            }

            // ── ✅ Step 3：封面仍为空 → 发布阶段补救刮削 ──
            // 覆盖场景：前端没传 pic，或 parse 阶段三级兜底都失败了
            if (finalCoverUrl == null || finalCoverUrl.trim().isEmpty()) {
                log.info("▶ 封面为空，开始发布阶段补救刮削，歌曲：{}", name.trim());
                String scrapedCover = null;

                // 优先网易云
                try {
                    scrapedCover = neteaseScraperService.fetchSongCover(name.trim(), singer);
                } catch (Exception e) {
                    log.warn("⚠️ 网易云封面补救失败: {}", e.getMessage());
                }

                // 网易云没找到则用苹果 iTunes
                if (scrapedCover == null || scrapedCover.trim().isEmpty()) {
                    try {
                        MusicScraperUtil.ScrapedData scraped =
                                MusicScraperUtil.scrapeFromApple(name.trim(), singer);
                        if (scraped != null) scrapedCover = scraped.getCoverUrl();
                    } catch (Exception e) {
                        log.warn("⚠️ 苹果 iTunes 封面补救失败: {}", e.getMessage());
                    }
                }

                if (scrapedCover != null && !scrapedCover.trim().isEmpty()) {
                    finalCoverUrl = downloadAndUploadCoverToMinio(scrapedCover);
                    if (finalCoverUrl != null) {
                        log.info("✅ 发布阶段封面补救成功: {}", finalCoverUrl);
                    } else {
                        log.warn("⚠️ 发布阶段封面补救：刮削到但上传 MinIO 失败");
                    }
                } else {
                    log.warn("⚠️ 发布阶段封面补救：所有刮削源均未找到封面，歌曲：{}", name.trim());
                }
            }

            // ── Step 4：解析/创建歌手 ──
            Long targetArtistId = null;
            if (singer != null && !singer.trim().isEmpty()) {
                targetArtistId = resolveArtistId(singer.trim(), finalCoverUrl);
            }

            // ── Step 5：写入数据库 ──
            Song newSong = new Song();
            newSong.setSongName(name.trim());
            newSong.setUserId(currentUserId);
            newSong.setStatus(0); // 私人，等待本人申请审核
            if (targetArtistId != null) newSong.setArtistId(targetArtistId);
            newSong.setAlbum(album);
            newSong.setAudioUrl(finalAudioUrl);
            newSong.setCoverUrl(finalCoverUrl);
            if (duration != null) newSong.setDuration(duration);
            newSong.setReleaseTime(LocalDate.now());
            newSong.setLyric(lyric);
            newSong.setPlayCount(0L);

            if (!songService.save(newSong)) return Result.fail("写入数据库失败");

            Map<String, Object> returnData = new HashMap<>();
            returnData.put("id",         newSong.getSongId());
            returnData.put("songId",     newSong.getSongId());
            returnData.put("songName",   newSong.getSongName());
            returnData.put("coverUrl",   newSong.getCoverUrl());
            returnData.put("audioUrl",   newSong.getAudioUrl());
            returnData.put("lyric",      newSong.getLyric());
            returnData.put("singerName", singer != null ? singer : "未知歌手");
            returnData.put("status",     0);
            return Result.success(returnData);

        } catch (Exception e) {
            log.error("发布歌曲失败", e);
            return Result.fail("发布失败：" + e.getMessage());
        }
    }

    // ================================================================
    // 重新提交审核 / 管理员直接发布 / 我的歌曲列表 / 申请发布 / 删除
    // ================================================================

    /**
     * 被拒歌曲重新提交审核（status: -1 → 1）
     */
    @PutMapping("/{id}/resubmit")
    public Result<?> resubmitSong(@PathVariable Long id) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (!currentUserId.equals(song.getUserId())) return Result.fail("无权操作他人歌曲");
        if (song.getStatus() == null || song.getStatus() != Song.STATUS_REJECTED)
            return Result.fail("只有被拒歌曲才能重新提交审核");

        LambdaUpdateWrapper<Song> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Song::getSongId, id)
                .set(Song::getStatus, Song.STATUS_AUDITING)
                .set(Song::getRejectReason, null);
        songService.update(updateWrapper);
        return Result.success("已重新提交审核，请等待管理员审核");
    }

    /**
     * 管理员/爬虫直接写入平台歌曲（status=2 公开，userId=null）
     */
    @PostMapping("/parse_singer")
    public Result<?> addSong(@RequestBody SongParseDTO dto) {
        if (dto.getName() == null || dto.getSongUrl() == null)
            return Result.fail("歌曲名称和音频地址不能为空");

        Long targetArtistId = null;
        if (dto.getSinger() != null && !dto.getSinger().trim().isEmpty()) {
            LambdaQueryWrapper<Artist> artistWrapper = new LambdaQueryWrapper<>();
            artistWrapper.eq(Artist::getArtistName, dto.getSinger());
            Artist existArtist = artistService.getOne(artistWrapper);
            if (existArtist != null) {
                targetArtistId = existArtist.getArtistId();
            } else {
                Artist newArtist = new Artist();
                newArtist.setArtistName(dto.getSinger());
                if (dto.getPic() != null) newArtist.setAvatar(dto.getPic());
                artistService.save(newArtist);
                targetArtistId = newArtist.getArtistId();
            }
        }

        Song newSong = new Song();
        newSong.setSongName(dto.getName());
        newSong.setStatus(2);    // 平台公开歌曲
        newSong.setUserId(null); // 无归属用户
        if (targetArtistId != null) newSong.setArtistId(targetArtistId);
        newSong.setAlbum(dto.getAlbum());
        newSong.setAudioUrl(dto.getSongUrl());
        newSong.setCoverUrl(dto.getPic());
        newSong.setDuration(dto.getDuration());
        newSong.setReleaseTime(LocalDate.now());
        newSong.setLyric(dto.getLyric());
        newSong.setPlayCount(0L);

        return songService.save(newSong) ? Result.success(newSong) : Result.fail("写入数据库失败");
    }

    /**
     * 我上传的所有歌曲（含全部状态）
     */
    @GetMapping("/my")
    public Result<?> getMySongs() {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        return Result.success(buildMySongList(currentUserId,
                Arrays.asList(Song.STATUS_PRIVATE, Song.STATUS_AUDITING, Song.STATUS_PUBLIC, Song.STATUS_REJECTED)));
    }

    /**
     * 我的私人/审核中/被拒歌曲列表（不含公开歌曲）
     */
    @GetMapping("/private")
    public Result<?> getMyPrivateSongs() {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        return Result.success(buildMySongList(currentUserId,
                Arrays.asList(Song.STATUS_PRIVATE, Song.STATUS_AUDITING, Song.STATUS_REJECTED)));
    }

    /** 构建"我的歌曲"列表数据（复用方法） */
    private List<Map<String, Object>> buildMySongList(Long currentUserId, List<Integer> statuses) {
        List<Song> songs = songService.lambdaQuery()
                .eq(Song::getUserId, currentUserId)
                .in(Song::getStatus, statuses)
                .orderByDesc(Song::getSongId)
                .list();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Song song : songs) {
            Map<String, Object> map = new HashMap<>();
            map.put("songId",       song.getSongId());
            map.put("songName",     song.getSongName());
            map.put("coverUrl",     song.getCoverUrl());
            map.put("audioUrl",     song.getAudioUrl());
            map.put("duration",     song.getDuration());
            map.put("singerName",   resolveSingerName(song.getArtistId()));
            map.put("album",        song.getAlbum());
            map.put("lyric",        song.getLyric());
            map.put("status",       song.getStatus());
            map.put("rejectReason", song.getRejectReason());
            result.add(map);
        }
        return result;
    }

    /**
     * 私人歌曲申请发布到平台（status: 0 → 1 审核中）
     */
    @PutMapping("/{id}/apply")
    public Result<?> applyPublish(@PathVariable Long id) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (!currentUserId.equals(song.getUserId())) return Result.fail("无权操作他人歌曲");
        if (song.getStatus() == null || song.getStatus() != Song.STATUS_PRIVATE)
            return Result.fail("只有私人歌曲才能申请发布");

        LambdaUpdateWrapper<Song> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Song::getSongId, id)
                .set(Song::getStatus, Song.STATUS_AUDITING)
                .set(Song::getRejectReason, null);
        songService.update(updateWrapper);
        return Result.success("已提交审核，请等待管理员审核");
    }

    /**
     * 获取我的某首歌曲详情（仅本人可查）
     */
    @GetMapping("/my/{id}")
    public Result<?> getMySongDetail(@PathVariable Long id) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) return Result.fail("用户未登录");
        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        if (!currentUserId.equals(song.getUserId())) return Result.fail("无权查看他人歌曲");

        Map<String, Object> map = new HashMap<>();
        map.put("songId",       song.getSongId());
        map.put("songName",     song.getSongName());
        map.put("coverUrl",     song.getCoverUrl());
        map.put("audioUrl",     song.getAudioUrl());
        map.put("duration",     song.getDuration());
        map.put("singerName",   resolveSingerName(song.getArtistId()));
        map.put("album",        song.getAlbum());
        map.put("lyric",        song.getLyric());
        map.put("status",       song.getStatus());
        map.put("rejectReason", song.getRejectReason());
        return Result.success(map);
    }

    /**
     * 删除歌曲（仅本人可删）
     * 同步删除 MinIO 中的音频和封面文件
     * ⚠️ 注意：@DeleteMapping("/{id}") 对应的路径变量名必须是 id，原代码写成 songId 会导致 404
     */
    @DeleteMapping("/{id}")
    public Result<?> deleteSong(@PathVariable Long id) {
        Long userId = UserHolder.getUserId();
        Song song = songService.getById(id);
        if (song == null) return Result.fail("歌曲不存在");
        // 平台歌曲 userId 为 null，未登录时 userId 也为 null，均需判空避免 NPE
        if (userId == null || song.getUserId() == null || !song.getUserId().equals(userId))
            return Result.fail("无权限删除");

        songService.removeById(id);
        deleteMinioFile(song.getAudioUrl());
        deleteMinioFile(song.getCoverUrl());
        return Result.success();
    }

    // ================================================================
    // 私有工具方法
    // ================================================================

    /**
     * ✅ 核心工具：下载外链图片（iTunes / 网易云封面）并上传到 MinIO，返回永久 URL
     * 规则：
     *   - 已经是 MinIO 地址 → 直接返回，不重复上传
     *   - 非 http 开头 → 返回 null，不处理
     *   - 下载/上传失败 → 返回 null，不抛出异常，由调用方处理
     */
    private String downloadAndUploadCoverToMinio(String coverUrl) {
        if (coverUrl == null || coverUrl.trim().isEmpty()) return null;
        // 已经是 MinIO 内部地址，直接返回
        if (coverUrl.startsWith(minioEndpoint)) return coverUrl;
        // 只处理 http 开头的外链
        if (!coverUrl.startsWith("http")) return null;

        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(coverUrl, byte[].class);
            byte[] imageBytes = resp.getBody();
            if (imageBytes == null || imageBytes.length == 0) {
                log.error("❌ 封面下载失败（空响应）: {}", coverUrl);
                return null;
            }

            // 根据响应 Content-Type 判断图片格式
            String contentType = "image/jpeg";
            if (resp.getHeaders().getContentType() != null)
                contentType = resp.getHeaders().getContentType().toString();
            String suffix = contentType.contains("png") ? ".png" : ".jpg";

            String objectName = "songCovers/" + UUID.randomUUID() + suffix;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET_NAME).object(objectName)
                    .stream(new ByteArrayInputStream(imageBytes), imageBytes.length, -1)
                    .contentType(contentType).build());

            return minioEndpoint + "/" + BUCKET_NAME + "/" + objectName;
        } catch (Exception e) {
            log.error("❌ 封面下载/上传 MinIO 失败: {}（URL: {}）", e.getMessage(), coverUrl);
            return null;
        }
    }

    /**
     * 从 MinIO URL 中提取 objectName 并删除文件
     * 只删属于本系统 MinIO 的文件，外链（iTunes / 网易云）不处理
     */
    private void deleteMinioFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) return;
        // 只删 MinIO 内部文件
        String host = minioEndpoint.replace("http://", "").replace("https://", "");
        if (!fileUrl.contains(host)) return;
        try {
            String path = new URL(fileUrl).getPath(); // /bucket/objectName
            String[] parts = path.split("/", 3);      // ["", "bucket", "objectName"]
            if (parts.length < 3) return;
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(parts[1]).object(parts[2]).build());
            log.info("✅ MinIO 文件已删除: {}", parts[2]);
        } catch (Exception e) {
            log.error("❌ MinIO 文件删除失败: {} → {}", fileUrl, e.getMessage());
        }
    }

    /**
     * 上传音频文件到 MinIO，返回永久访问 URL
     */
    private String uploadAudioToMinio(MultipartFile file) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String suffix = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".mp3";
        String objectName = "songs/" + UUID.randomUUID() + suffix;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(BUCKET_NAME).object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType()).build());
        return minioEndpoint + "/" + BUCKET_NAME + "/" + objectName;
    }

    /**
     * 处理封面入库：
     *   - Base64  → 解码 → 上传 MinIO → 返回 MinIO URL
     *   - 外链    → 下载 → 上传 MinIO → 返回 MinIO URL
     *   - 其他    → 原样返回（理论上不应出现）
     */
    private String resolveCoverUrl(String pic) throws Exception {
        if (pic == null || pic.trim().isEmpty()) return null;

        if (pic.startsWith("data:image")) {
            // Base64 解码上传
            String[] parts = pic.split(",");
            byte[] imageBytes = Base64.getDecoder().decode(parts[1]);
            String suffix = pic.contains("png") ? ".png" : ".jpg";
            String objectName = "songCovers/" + UUID.randomUUID() + suffix;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET_NAME).object(objectName)
                    .stream(new ByteArrayInputStream(imageBytes), imageBytes.length, -1)
                    .contentType(pic.contains("png") ? "image/png" : "image/jpeg").build());
            return minioEndpoint + "/" + BUCKET_NAME + "/" + objectName;
        }

        if (pic.startsWith("http")) {
            // 外链下载上传
            return downloadAndUploadCoverToMinio(pic);
        }

        return pic;
    }

    /**
     * 解析歌手：已存在则返回 ID，不存在则新建后返回 ID
     */
    private Long resolveArtistId(String singer, String coverUrl) {
        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Artist::getArtistName, singer);
        Artist existArtist = artistService.getOne(wrapper);
        if (existArtist != null) return existArtist.getArtistId();

        Artist newArtist = new Artist();
        newArtist.setArtistName(singer);
        newArtist.setAvatar(coverUrl);
        artistService.save(newArtist);
        return newArtist.getArtistId();
    }

    /**
     * 通过 artistId 查询歌手名，找不到返回"未知歌手"
     * 抽成公共方法，避免重复代码
     */
    private String resolveSingerName(Long artistId) {
        if (artistId == null) return "未知歌手";
        Artist artist = artistService.getById(artistId);
        return artist != null ? artist.getArtistName() : "未知歌手";
    }
}
