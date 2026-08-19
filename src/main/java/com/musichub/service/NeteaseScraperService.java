package com.musichub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musichub.entity.Artist;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class NeteaseScraperService {

    @Autowired
    @Qualifier("timeoutRestTemplate")
    private RestTemplate restTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${netease.api.base-url:http://127.0.0.1:3000}")
    private String baseUrl;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket-name:vibe-music-data}")
    private String bucketName;

    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    // ================================================================
    // 歌手信息刮削
    // ================================================================

    /**
     * 刮削歌手信息（头像 + 简介），直接回填到 artist 对象
     * 头像下载后存入 MinIO，数据库统一存 MinIO URL
     */
    public void fetchAndFillArtistInfo(Artist artist) {
        if (artist == null || artist.getArtistName() == null) return;
        String originalName = artist.getArtistName().trim();

        try {
            // 多歌手用 / 或 & 分隔时，只取第一个主歌手搜索
            String mainArtistName = originalName.split("[/&]")[0].trim();
            JsonNode bestMatch = searchArtist(mainArtistName);

            // 第一次没找到，去掉括号内容再试一次
            if (bestMatch == null) {
                String cleanName = mainArtistName.replaceAll("\\s*[\\(（].*", "").trim();
                bestMatch = searchArtist(cleanName);
            }

            if (bestMatch == null) return;

            String neteaseArtistId = bestMatch.get("id").asText();

            // 1. 下载头像并存入 MinIO
            if (bestMatch.has("picUrl") && !bestMatch.get("picUrl").isNull()) {
                String neteasePicUrl = bestMatch.get("picUrl").asText() + "?param=500y500";
                try {
                    ResponseEntity<byte[]> response = restTemplate.getForEntity(neteasePicUrl, byte[].class);
                    byte[] imageBytes = response.getBody();
                    if (imageBytes != null) {
                        String fileName = "artists/" + UUID.randomUUID() + ".jpg";
                        InputStream inputStream = new ByteArrayInputStream(imageBytes);
                        minioClient.putObject(
                                PutObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(fileName)
                                        .stream(inputStream, imageBytes.length, -1)
                                        .contentType("image/jpeg")
                                        .build()
                        );
                        String finalUrl = minioEndpoint + "/" + bucketName + "/" + fileName;
                        artist.setAvatar(finalUrl);
                        System.out.println("✅ 歌手头像已存入 MinIO: " + finalUrl);
                    }
                } catch (Exception e) {
                    System.out.println("❌ 歌手头像导入 MinIO 失败: " + e.getMessage());
                }
            }

            // 2. 获取歌手简介 (使用 desc 接口)
            String descUrl = baseUrl + "/artist/desc?id=" + neteaseArtistId;
            String descResult = restTemplate.getForObject(descUrl, String.class);
            JsonNode descNode = objectMapper.readTree(descResult);
            String bio = "";
            if (descNode.has("briefDesc") && !descNode.get("briefDesc").isNull()) {
                bio = descNode.get("briefDesc").asText();
                if (!bio.trim().isEmpty()) {
                    artist.setIntroduction(bio);
                }
            }

            // 3. 获取歌手标签/曲风/类型 (使用 detail 接口)
            try {
                String detailUrl = baseUrl + "/artist/detail?id=" + neteaseArtistId;
                String detailResult = restTemplate.getForObject(detailUrl, String.class);
                JsonNode artistNode = objectMapper.readTree(detailResult).path("data").path("artist");

                String styleTag = "";

                // 优先从网易云返回的身份标识 identities 里拿，例如 ["华语男歌手", "流行男歌手"]
                JsonNode identities = artistNode.path("identities");
                if (identities.isArray() && identities.size() > 0) {
                    styleTag = identities.get(0).asText();
                } else {
                    // 如果网易云没有返回明确标签，我们就用之前提到的简介本地猜算法兜底
                    String bioLower = bio.toLowerCase();
                    if (bioLower.contains("乐队") || bioLower.contains("band")) styleTag = "知名乐队";
                    else if (bioLower.contains("组合") || bioLower.contains("group")) styleTag = "流行组合";
                    else if (bioLower.contains("制作人")) styleTag = "音乐制作人";
                    else if (bioLower.contains("dj") || bioLower.contains("电音")) styleTag = "电子音乐人";
                    else if (bioLower.contains("说唱") || bioLower.contains("rapper")) styleTag = "说唱歌手";
                    else if (bioLower.contains("摇滚") || bioLower.contains("rock")) styleTag = "摇滚音乐人";
                    else if (bioLower.contains("中国") || bioLower.contains("华语")) styleTag = "华语歌手";
                    else styleTag = "独立音乐人"; // 啥都不知道就叫独立音乐人
                }

                artist.setStyle(styleTag);
                System.out.println("✅ 抓取到歌手标签: " + styleTag);

            } catch (Exception e) {
                System.out.println("❌ 获取歌手详情(标签)失败: " + e.getMessage());
            }

        } catch (Exception e) {
            System.out.println("歌手信息抓取异常: " + e.getMessage());
        }
    }

    /**
     * 在网易云搜索歌手，返回最匹配的第一条结果节点
     * 先用 /cloudsearch，失败则降级到 /search
     */
    private JsonNode searchArtist(String keyword) {
        try {
            System.out.println("====== 搜索歌手: " + keyword + " ======");

            java.net.URI cloudUri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/cloudsearch")
                    .queryParam("keywords", keyword)
                    .queryParam("type", 100)
                    .queryParam("limit", 1)
                    .build()
                    .encode(java.nio.charset.StandardCharsets.UTF_8)
                    .toUri();

            String searchResult = restTemplate.getForObject(cloudUri, String.class);
            JsonNode artistsArray = objectMapper.readTree(searchResult).path("result").path("artists");

            if (!artistsArray.isMissingNode() && artistsArray.size() > 0) {
                return artistsArray.get(0);
            }

            // 降级到普通 /search 接口
            System.out.println("cloudsearch 未找到，降级使用 /search...");
            java.net.URI searchUri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/search")
                    .queryParam("keywords", keyword)
                    .queryParam("type", 100)
                    .queryParam("limit", 1)
                    .build()
                    .encode(java.nio.charset.StandardCharsets.UTF_8)
                    .toUri();

            String fallbackResult = restTemplate.getForObject(searchUri, String.class);
            JsonNode fallbackArtists = objectMapper.readTree(fallbackResult).path("result").path("artists");

            if (!fallbackArtists.isMissingNode() && fallbackArtists.size() > 0) {
                return fallbackArtists.get(0);
            }

        } catch (Exception e) {
            System.out.println("❌ 搜索歌手失败: " + e.getMessage());
        }
        System.out.println("❌ 网易云查无此歌手: " + keyword);
        return null;
    }

    // ================================================================
    // 歌词刮削
    // ================================================================

    /**
     * 从网易云抓取歌词
     * 搜索策略：
     *   第一轮：歌曲名 + 歌手多变体组合搜索，取综合评分最高的候选
     *   第二轮：仅歌曲名搜索（第一轮完全没命中时）
     */
    public String fetchLyricFromNetease(String songName, String artistName) {
        System.out.println("====== 开始抓取网易云歌词 ======");
        System.out.println("🎵 [网易云歌词] 输入参数 | 歌曲名=" + songName + " | 歌手=" + artistName);
        
        try {
            String cleanSong = normalizeText(songName);
            List<String> artistVariants = buildArtistVariants(artistName);
            List<String> queryVariants  = buildSongQueryVariants(songName);

            System.out.println("📝 [网易云歌词] 文本标准化 | 原始歌曲名: " + songName + " → 标准化: " + cleanSong);
            System.out.println("👤 [网易云歌词] 歌手变体列表: " + artistVariants);

            JsonNode bestSong = null;
            int bestTotalScore = -1;

            // 第一轮：歌名 + 歌手变体组合搜索
            System.out.println("🔍 [网易云歌词-第一轮] 开始歌名+歌手组合搜索...");
            for (String qSong : queryVariants) {
                for (String qArtist : artistVariants) {
                    String keyword   = (qSong + " " + qArtist).trim();
                    System.out.println("  🔎 尝试关键词: \"" + keyword + "\"");
                    JsonNode candidate = searchBestSongCandidate(keyword, cleanSong, artistVariants);
                    if (candidate != null) {
                        int total = calcTitleScore(cleanSong, candidate.path("name").asText(""))
                                + calcBestArtistScore(artistVariants, extractFirstArtist(candidate));
                        System.out.println("  ✅ 组合命中: \"" + keyword + "\" → 候选: \"" 
                                + candidate.path("name").asText() + "\" | score=" + total);
                        if (total > bestTotalScore) { bestTotalScore = total; bestSong = candidate; }
                    } else {
                        System.out.println("  ❌ 无结果");
                    }
                }
            }

            // 第二轮：仅歌曲名
            if (bestSong == null) {
                System.out.println("⚠️ [网易云歌词-第二轮] 第一轮无结果，尝试仅歌名搜索...");
                for (String qSong : queryVariants) {
                    System.out.println("  🔎 尝试关键词: \"" + qSong + "\"");
                    JsonNode candidate = searchBestSongCandidate(qSong, cleanSong, artistVariants);
                    if (candidate != null) {
                        int total = calcTitleScore(cleanSong, candidate.path("name").asText(""))
                                + calcBestArtistScore(artistVariants, extractFirstArtist(candidate));
                        System.out.println("  ✅ 仅歌名命中: \"" + qSong + "\" → 候选: \"" 
                                + candidate.path("name").asText() + "\" | score=" + total);
                        if (total > bestTotalScore) { bestTotalScore = total; bestSong = candidate; }
                    } else {
                        System.out.println("  ❌ 无结果");
                    }
                }
            }

            if (bestSong == null) {
                System.out.println("❌ [网易云歌词] 未找到可靠候选，歌词抓取结束");
                return null;
            }

            String finalSongId = bestSong.path("id").asText();
            System.out.println("✅ [网易云歌词] 最终选定 | 歌曲: " + bestSong.path("name").asText()
                    + " | 歌手: " + extractFirstArtist(bestSong) 
                    + " | ID=" + finalSongId 
                    + " | 综合得分=" + bestTotalScore);

            // 拉取歌词
            String lyricUrl = baseUrl + "/lyric?id=" + finalSongId;
            System.out.println("🌐 [网易云歌词] 请求歌词接口 URL: " + lyricUrl);
            
            String lyricResult = restTemplate.getForObject(lyricUrl, String.class);
            
            if (lyricResult == null || lyricResult.trim().isEmpty()) {
                System.out.println("❌ [网易云歌词] 接口返回为空");
                return null;
            }
            
            System.out.println("📦 [网易云歌词] 接口响应长度: " + lyricResult.length() + " 字符");
            
            JsonNode lyricNode = objectMapper.readTree(lyricResult);
            String finalLyric  = lyricNode.path("lrc").path("lyric").asText(null);

            if (finalLyric != null && !finalLyric.trim().isEmpty()) {
                System.out.println("✅ [网易云歌词] 成功抓取！歌词字数: " + finalLyric.length());
                System.out.println("====== 抓取歌词结束 ======");
                return finalLyric;
            } else {
                System.out.println("⚠️ [网易云歌词] 接口有返回但 lrc.lyric 字段为空");
            }

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (e.getClass().getSimpleName().contains("Timeout") || 
                (errorMsg != null && (errorMsg.contains("timeout") || errorMsg.contains("timed out")))) {
                System.err.println("❌ [网易云歌词] 请求超时 | 错误类型=" + e.getClass().getSimpleName() 
                        + " | 错误信息=" + errorMsg);
                System.err.println("💡 [提示] 网易云 API 服务响应过慢或不可达，请检查网络连接或服务状态");
            } else {
                System.err.println("❌ [网易云歌词] 抓取异常 | 错误类型=" + e.getClass().getSimpleName() 
                        + " | 错误信息=" + errorMsg);
            }
            e.printStackTrace();
        }

        System.out.println("❌ [网易云歌词] 无歌词数据");
        System.out.println("====== 抓取歌词结束 ======");
        return null;
    }

    // ================================================================
    // ✅ 歌曲封面刮削（新增，供 SongController 调用）
    // ================================================================

    /**
     * 从网易云搜索歌曲封面 URL（外链，调用方需自行下载并存入 MinIO）
     * 封面取搜索结果第一条可靠候选的 al.picUrl（即专辑封面）
     *
     * @param songName   歌曲名
     * @param artistName 歌手名（可为 null）
     * @return 封面外链 URL（已附加 ?param=500y500），找不到返回 null
     */
    public String fetchSongCover(String songName, String artistName) {
        if (songName == null || songName.trim().isEmpty()) return null;
        System.out.println("====== 开始刮削歌曲封面: " + songName + " - " + artistName + " ======");

        try {
            String cleanSong      = normalizeText(songName);
            List<String> artistVariants = buildArtistVariants(artistName);

            JsonNode bestSong  = null;
            int bestScore      = -1;

            // 第一轮：歌名 + 歌手变体组合搜索
            for (String qArtist : artistVariants) {
                // 跳过空歌手（空的放第二轮单独处理）
                if (qArtist.trim().isEmpty()) continue;
                String keyword   = (cleanSong + " " + qArtist).trim();
                JsonNode candidate = searchBestSongCandidate(keyword, cleanSong, artistVariants);
                if (candidate != null) {
                    int score = calcTitleScore(cleanSong, candidate.path("name").asText(""))
                            + calcBestArtistScore(artistVariants, extractFirstArtist(candidate));
                    System.out.println("封面搜索命中: " + keyword + " → score=" + score);
                    if (score > bestScore) { bestScore = score; bestSong = candidate; }
                }
            }

            // 第二轮：只用歌名兜底
            if (bestSong == null) {
                JsonNode candidate = searchBestSongCandidate(cleanSong, cleanSong, artistVariants);
                if (candidate != null) {
                    int score = calcTitleScore(cleanSong, candidate.path("name").asText(""))
                            + calcBestArtistScore(artistVariants, extractFirstArtist(candidate));
                    System.out.println("封面仅歌名命中: " + cleanSong + " → score=" + score);
                    if (score > bestScore) { bestScore = score; bestSong = candidate; }
                }
            }

            if (bestSong == null) {
                System.out.println("⚠️ 未找到可靠歌曲候选，封面刮削失败");
                return null;
            }

            // 从搜索结果的 al.picUrl 取专辑封面
            // 网易云歌曲搜索返回结构：songs[].al.picUrl
            JsonNode al = bestSong.path("al");
            if (!al.isMissingNode() && !al.path("picUrl").isMissingNode()) {
                String picUrl = al.path("picUrl").asText(null);
                if (picUrl != null && !picUrl.trim().isEmpty()) {
                    // 附加尺寸参数，获取 500x500 高清封面
                    String finalUrl = picUrl + "?param=500y500";
                    System.out.println("✅ 找到歌曲封面: " + finalUrl);
                    System.out.println("====== 封面刮削结束 ======");
                    return finalUrl;
                }
            }

            // 部分老接口返回的是 album.blurPicUrl，做一次兜底
            JsonNode album = bestSong.path("album");
            if (!album.isMissingNode()) {
                String blurPic = album.path("blurPicUrl").asText(null);
                if (blurPic == null || blurPic.trim().isEmpty()) {
                    blurPic = album.path("picUrl").asText(null);
                }
                if (blurPic != null && !blurPic.trim().isEmpty()) {
                    String finalUrl = blurPic + "?param=500y500";
                    System.out.println("✅ 从 album 字段找到封面（兜底）: " + finalUrl);
                    System.out.println("====== 封面刮削结束 ======");
                    return finalUrl;
                }
            }

            System.out.println("⚠️ 候选歌曲没有封面字段，歌曲: " + bestSong.path("name").asText());

        } catch (Exception e) {
            System.out.println("❌ 歌曲封面刮削异常: " + e.getMessage());
        }

        System.out.println("====== 封面刮削结束 ======");
        return null;
    }

    // ================================================================
    // 通用搜索候选（歌词 + 封面共用）
    // ================================================================

    /**
     * 在网易云搜索歌曲候选列表，从前 10 条结果中选出评分最高的一条
     * 先用 /cloudsearch，失败则降级到 /search
     */
    private JsonNode searchBestSongCandidate(String keyword, String cleanTargetSong, List<String> artistVariants) {
        try {
            System.out.println("🔍 搜索: " + keyword);

            java.net.URI cloudUri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/cloudsearch")
                    .queryParam("keywords", keyword)
                    .queryParam("type", 1)
                    .queryParam("limit", 10)
                    .build()
                    .encode(java.nio.charset.StandardCharsets.UTF_8)
                    .toUri();

            String cloudResult = restTemplate.getForObject(cloudUri, String.class);
            JsonNode songsArray = objectMapper.readTree(cloudResult).path("result").path("songs");

            // 降级到 /search
            if (songsArray.isMissingNode() || !songsArray.isArray() || songsArray.size() == 0) {
                java.net.URI searchUri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/search")
                        .queryParam("keywords", keyword)
                        .queryParam("type", 1)
                        .queryParam("limit", 10)
                        .build()
                        .encode(java.nio.charset.StandardCharsets.UTF_8)
                        .toUri();
                String searchResult = restTemplate.getForObject(searchUri, String.class);
                songsArray = objectMapper.readTree(searchResult).path("result").path("songs");
            }

            if (songsArray.isMissingNode() || !songsArray.isArray() || songsArray.size() == 0) {
                return null;
            }

            JsonNode best      = null;
            int bestTotal      = -1;

            for (JsonNode node : songsArray) {
                String candidateName   = node.path("name").asText("");
                String candidateArtist = extractFirstArtist(node);

                // 标题可靠性过滤：不可靠的候选直接淘汰
                if (!isReliableTitleMatch(cleanTargetSong, candidateName)) continue;

                int titleScore  = calcTitleScore(cleanTargetSong, candidateName);
                int artistScore = calcBestArtistScore(artistVariants, candidateArtist);
                int total       = titleScore + artistScore;

                // 纯英文歌曲：歌手完全不匹配则淘汰
                if (isMostlyEnglish(cleanTargetSong) && !artistVariants.isEmpty()
                        && !artistVariants.get(0).isBlank() && artistScore == 0) continue;

                if (total > bestTotal) { bestTotal = total; best = node; }
            }

            return best;

        } catch (Exception e) {
            System.out.println("❌ 搜索候选异常，keyword=" + keyword + "，原因：" + e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 文本处理工具
    // ================================================================

    /** 构建歌曲名搜索变体列表（原始、标准化、去括号、去 feat 等） */
    private List<String> buildSongQueryVariants(String songName) {
        Set<String> set = new LinkedHashSet<>();
        if (songName == null) return new ArrayList<>();

        String raw        = songName.trim();
        String normalized = normalizeText(raw);
        String noBracket  = raw.replaceAll("\\s*[\\(（].*", "").trim();

        set.add(raw);
        if (!normalized.isEmpty()) set.add(normalized);
        if (!noBracket.isEmpty())  set.add(noBracket);

        // 去除 feat / live / inst 等附属标记
        String soft = raw
                .replaceAll("(?i)\\bfeat\\.?\\b.*", "")
                .replaceAll("(?i)\\bft\\.?\\b.*", "")
                .replaceAll("(?i)\\blive\\b.*", "")
                .replaceAll("(?i)\\binst\\.?\\b.*", "")
                .replaceAll("伴奏", "").replaceAll("纯音乐", "").trim();
        if (!soft.isEmpty()) set.add(normalizeText(soft));

        return new ArrayList<>(set);
    }

    /** 构建歌手名搜索变体列表（原始、标准化、去括号、分割多歌手等） */
    private List<String> buildArtistVariants(String artistName) {
        Set<String> set = new LinkedHashSet<>();
        if (artistName == null || artistName.trim().isEmpty()) {
            set.add(""); return new ArrayList<>(set);
        }

        String raw        = artistName.trim();
        String normalized = normalizeText(raw);
        String noBracket  = raw.replaceAll("\\s*[\\(（].*", "").trim();

        set.add(raw);
        if (!normalized.isEmpty()) set.add(normalized);
        if (!noBracket.isEmpty())  set.add(noBracket);

        if (raw.contains("/")) set.add(normalizeText(raw.split("/")[0]));
        if (raw.contains("&")) set.add(normalizeText(raw.split("&")[0]));
        if (raw.contains(",")) set.add(normalizeText(raw.split(",")[0]));

        String noLatinPrefix = normalized.replaceAll("^[A-Za-z.\\s]+", "").trim();
        if (!noLatinPrefix.isEmpty()) set.add(noLatinPrefix);

        String soft = normalized.replaceAll("(?i)\\bsolo\\b", "")
                .replaceAll("(?i)\\bgroup\\b", "")
                .replaceAll("\\s+", " ").trim();
        if (!soft.isEmpty()) set.add(soft);

        set.removeIf(String::isBlank);
        if (set.isEmpty()) set.add("");
        return new ArrayList<>(set);
    }

    /**
     * 文本标准化：全角转半角、去括号内容、去常见附属标记、合并多余空格
     */
    private String normalizeText(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replace('（', '(').replace('）', ')').replace('【', '[').replace('】', ']')
                .replace('：', ':').replace('－', '-');
        t = t.replaceAll("(?i)\\bfeat\\.?\\b", " ").replaceAll("(?i)\\bft\\.?\\b", " ")
                .replaceAll("(?i)\\bver\\.?\\b", " ").replaceAll("(?i)\\blive\\b", " ")
                .replaceAll("(?i)\\bmv\\b", " ").replaceAll("(?i)\\binst\\.?\\b", " ")
                .replaceAll("伴奏", " ").replaceAll("纯音乐", " ");
        t = t.replaceAll("\\([^)]*\\)", " ").replaceAll("（[^）]*）", " ")
                .replaceAll("\\[[^\\]]*\\]", " ").replaceAll("【[^】]*】", " ");
        t = t.replaceAll("[·•]", " ").replaceAll("\\.", " ")
                .replaceAll("_", " ").replaceAll("-", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * 从歌曲节点中提取第一个歌手名
     * 兼容 ar（cloudsearch）、artists（search）、artist 三种字段结构
     */
    private String extractFirstArtist(JsonNode songNode) {
        if (songNode == null) return "";
        JsonNode ar = songNode.path("ar");
        if (ar.isArray() && ar.size() > 0) return ar.get(0).path("name").asText("");
        JsonNode artists = songNode.path("artists");
        if (artists.isArray() && artists.size() > 0) return artists.get(0).path("name").asText("");
        return songNode.path("artist").path("name").asText("");
    }

    // ================================================================
    // 评分算法
    // ================================================================

    /**
     * 标题可靠性初筛：过滤联唱集、组曲等明显不匹配的结果
     */
    private boolean isReliableTitleMatch(String expected, String actual) {
        String e = normalizeText(expected);
        String a = normalizeText(actual);
        if (e.isBlank() || a.isBlank()) return false;
        if (looksLikeMedleyOrTrackList(a)) return false;

        // 短中文歌名：必须完全一致
        if (containsCjk(e) && e.length() <= 4) return e.equalsIgnoreCase(a);

        // 英文歌名：候选不能超出原名 2.2 倍，且词序吻合
        if (isMostlyEnglish(e)) {
            if (a.length() > e.length() * 2.2) return false;
            return startsWithWordSequence(e.toLowerCase(), a.toLowerCase())
                    || sameCoreWords(e.toLowerCase(), a.toLowerCase());
        }

        return a.equalsIgnoreCase(e) || a.contains(e) || e.contains(a);
    }

    /** 计算标题匹配分（0 / 70 / 75 / 90 / 100） */
    private int calcTitleScore(String expected, String actual) {
        String e = normalizeText(expected);
        String a = normalizeText(actual);
        if (e.isBlank() || a.isBlank()) return 0;
        String el = e.toLowerCase(), al = a.toLowerCase();

        if (el.equals(al)) return 100;
        if (containsCjk(e) && e.length() <= 4) return 0; // 短中文不完全一致则 0 分

        if (isMostlyEnglish(e)) {
            if (startsWithWordSequence(el, al)) return 90;
            if (sameCoreWords(el, al)) return 75;
            return 0;
        }

        if (al.contains(el) || el.contains(al)) return 70;
        return 0;
    }

    /** 计算单个歌手匹配分（0 / 10 / 25 / 40） */
    private int calcArtistScore(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return 0;
        String e = normalizeText(expected).toLowerCase();
        String a = normalizeText(actual).toLowerCase();
        if (e.equals(a)) return 40;
        if (e.contains(a) || a.contains(e)) return 25;
        if (longestCommonSubstr(e, a) >= 2) return 10;
        return 0;
    }

    /** 取歌手变体列表中最高的匹配分 */
    private int calcBestArtistScore(List<String> artistVariants, String actualArtist) {
        int best = 0;
        for (String artist : artistVariants) {
            best = Math.max(best, calcArtistScore(artist, actualArtist));
        }
        return best;
    }

    // ================================================================
    // 辅助判断
    // ================================================================

    /** 判断是否看起来像联唱/组曲/曲目列表（过滤掉这类结果） */
    private boolean looksLikeMedleyOrTrackList(String s) {
        if (s == null) return true;
        String t = s.toLowerCase();
        if (t.length() > 80) return true;
        if (t.matches(".*\\b1\\..*\\b2\\..*")) return true;
        if (t.contains("movements:") || t.contains("preludes")
                || t.contains("carnaval") || t.contains("op. 9")) return true;
        return false;
    }

    /** 是否包含 CJK 字符（中文/日文/韩文） */
    private boolean containsCjk(String s) {
        if (s == null) return false;
        return s.matches(".*[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af].*");
    }

    /** 判断字符串是否以英文为主 */
    private boolean isMostlyEnglish(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.replaceAll("[^A-Za-z]", "");
        return !t.isBlank() && t.length() >= s.replaceAll("\\s+", "").length() / 2;
    }

    /** 判断 expected 的单词序列是否是 actual 的前缀 */
    private boolean startsWithWordSequence(String expected, String actual) {
        String[] ew = expected.trim().split("\\s+");
        String[] aw = actual.trim().split("\\s+");
        if (ew.length == 0 || aw.length < ew.length) return false;
        for (int i = 0; i < ew.length; i++) {
            if (!aw[i].equals(ew[i])) return false;
        }
        return true;
    }

    /** 判断两个字符串的核心词是否大部分重叠 */
    private boolean sameCoreWords(String expected, String actual) {
        String e = expected.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        String a = actual.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (e.isBlank() || a.isBlank()) return false;
        String[] ew = e.split("\\s+");
        int matched = 0;
        for (String word : ew) {
            if (word.length() <= 1) continue;
            if (a.contains(word)) matched++;
        }
        return matched >= Math.max(2, ew.length - 1);
    }

    /** 最长公共子串长度（用于歌手模糊匹配） */
    private int longestCommonSubstr(String s1, String s2) {
        int max = 0;
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    max = Math.max(max, dp[i][j]);
                }
            }
        }
        return max;
    }
}
