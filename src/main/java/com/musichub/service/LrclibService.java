package com.musichub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class LrclibService {

    @Autowired
    @Qualifier("timeoutRestTemplate")
    private RestTemplate restTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String fetchLyric(String trackName, String artistName, String duration) {
        try {
            if (trackName == null || trackName.trim().isEmpty()) {
                System.out.println("⚠️ [Lrclib歌词] 歌曲名为空，跳过抓取");
                return null;
            }

            if (artistName == null) artistName = "";
            if ("未知歌手".equals(artistName)) {
                artistName = "";
            }

            // 1. 清洗歌名 / 歌手名，去掉括号等杂质
            String cleanTrackName = cleanName(trackName);
            String cleanArtistName = cleanName(artistName);

            System.out.println("====== 开始抓取 Lrclib 歌词 ======");
            System.out.println("🎵 [Lrclib歌词] 输入参数 | 原名: " + trackName + " - " + artistName);
            System.out.println("📝 [Lrclib歌词] 文本标准化 | track_name: " + cleanTrackName + " | artist_name: " + cleanArtistName);

            HttpHeaders headers = new HttpHeaders();
            headers.set(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36 MusicHub/1.0"
            );
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 2. 优先使用模糊搜索 q 参数
            String fuzzyUrl = UriComponentsBuilder.fromHttpUrl("https://lrclib.net/api/search")
                    .queryParam("q", cleanTrackName + " " + cleanArtistName)
                    .toUriString();
            System.out.println("🔍 [Lrclib歌词-模糊搜索] 请求地址: " + fuzzyUrl);

            ResponseEntity<String> fuzzyResp = restTemplate.exchange(
                    fuzzyUrl, HttpMethod.GET, entity, String.class);

            System.out.println("📦 [Lrclib歌词-模糊搜索] HTTP状态码: " + fuzzyResp.getStatusCode());
            
            JsonNode resultArray = parseArray(fuzzyResp);
            
            if (resultArray == null || resultArray.size() == 0) {
                System.out.println("⚠️ [Lrclib歌词-模糊搜索] 无结果，尝试精确搜索...");
                
                // 3. 模糊搜索没有，尝试精确参数 track_name + artist_name
                String exactUrl = UriComponentsBuilder.fromHttpUrl("https://lrclib.net/api/search")
                        .queryParam("track_name", cleanTrackName)
                        .queryParam("artist_name", cleanArtistName)
                        .toUriString();
                System.out.println("🔍 [Lrclib歌词-精确搜索] 请求地址: " + exactUrl);

                ResponseEntity<String> exactResp = restTemplate.exchange(
                        exactUrl, HttpMethod.GET, entity, String.class);
                
                System.out.println("📦 [Lrclib歌词-精确搜索] HTTP状态码: " + exactResp.getStatusCode());
                
                resultArray = parseArray(exactResp);
            }

            if (resultArray == null || resultArray.size() == 0) {
                System.out.println("❌ [Lrclib歌词] 所有搜索方式均无结果，Lrclib 库中确实没有这首歌");
                return null;
            }

            System.out.println("✅ [Lrclib歌词] 搜索返回 " + resultArray.size() + " 条候选结果");

            // 4. 在返回的多条结果中选出最匹配的一条
            JsonNode bestMatch = selectBestMatch(resultArray, cleanTrackName, cleanArtistName);
            if (bestMatch == null) {
                System.out.println("❌ [Lrclib歌词] 虽然有返回结果，但没有找到足够相似的候选（得分过低）");
                return null;
            }

            System.out.println("✅ [Lrclib歌词] 成功匹配 | 歌曲: " + bestMatch.path("name").asText("") 
                    + " | 歌手: " + bestMatch.path("artistName").asText(""));

            boolean isInstrumental = bestMatch.path("instrumental").asBoolean(false);
            if (isInstrumental) {
                System.out.println("🎹 [Lrclib歌词] 判定为纯音乐");
                return "[00:00.00]纯音乐，请欣赏\n[00:05.00]🎵🎵🎵";
            }

            // 5. 优先返回动态歌词
            String syncedLyrics = bestMatch.path("syncedLyrics").asText(null);
            if (syncedLyrics != null && !syncedLyrics.trim().isEmpty()) {
                System.out.println("✅ [Lrclib歌词] 成功拿到动态歌词（逐字时间轴），长度: " + syncedLyrics.length() + " 字符");
                return syncedLyrics;
            }

            // 6. 退而求其次，返回静态歌词
            String plainLyrics = bestMatch.path("plainLyrics").asText(null);
            if (plainLyrics != null && !plainLyrics.trim().isEmpty()) {
                System.out.println("✅ [Lrclib歌词] 只拿到静态歌词（整句），长度: " + plainLyrics.length() + " 字符");
                return "[00:00.00]" + plainLyrics;
            }

            System.out.println("❌ [Lrclib歌词] 该条目没有任何歌词字段（syncedLyrics 和 plainLyrics 均为空）");
            return null;

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (e.getClass().getSimpleName().contains("Timeout") || 
                (errorMsg != null && (errorMsg.contains("timeout") || errorMsg.contains("timed out")))) {
                System.err.println("❌ [Lrclib歌词] 请求超时 | 错误类型=" + e.getClass().getSimpleName() 
                        + " | 错误信息=" + errorMsg);
                System.err.println("💡 [提示] Lrclib 服务响应过慢或不可达，请检查网络连接");
            } else {
                System.err.println("❌ [Lrclib歌词] 请求抛出异常 | 错误类型=" + e.getClass().getSimpleName() 
                        + " | 错误信息=" + errorMsg);
            }
            e.printStackTrace();
            return null;
        }
    }

    private JsonNode parseArray(ResponseEntity<String> resp) throws Exception {
        if (resp == null || !resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            if (resp != null) {
                System.out.println("抓取失败，HTTP状态码: " + resp.getStatusCode());
            }
            return null;
        }
        JsonNode root = objectMapper.readTree(resp.getBody());
        if (!root.isArray()) return null;
        return root;
    }

    private String cleanName(String s) {
        if (s == null) return "";
        // 去掉括号及其后内容
        String cleaned = s.replaceAll("\\s*[\\(（].*", "").trim();
        return cleaned;
    }

    private JsonNode selectBestMatch(JsonNode array, String targetTrack, String targetArtist) {
        JsonNode best = null;
        int bestScore = -1;

        for (JsonNode node : array) {
            String name = cleanName(node.path("name").asText(""));
            String artist = cleanName(node.path("artistName").asText(""));

            int score = calcSimilarityScore(targetTrack, name)
                    + calcSimilarityScore(targetArtist, artist);

            System.out.println("候选: " + name + " - " + artist + "，得分=" + score);

            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }

        // 可以根据需要设置一个最低分，比如 40，低于就认为不靠谱
        int MIN_SCORE = 40;
        if (bestScore < MIN_SCORE) {
            System.out.println("Lrclib 匹配得分过低（" + bestScore + "），放弃使用");
            return null;
        }

        return best;
    }

    /** 极简相似度：相等=100，包含=60，有较长公共子串=40，其他=0 */
    private int calcSimilarityScore(String expected, String actual) {
        if (expected == null || expected.isEmpty() || actual == null || actual.isEmpty()) {
            return 0;
        }
        String e = expected.toLowerCase();
        String a = actual.toLowerCase();
        if (e.equals(a)) return 100;
        if (e.contains(a) || a.contains(e)) return 60;
        int common = longestCommonSubstr(e, a);
        if (common >= Math.min(e.length(), a.length()) / 2) return 40;
        if (common >= 2) return 20;
        return 0;
    }

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
