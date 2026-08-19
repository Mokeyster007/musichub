package com.musichub.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.web.client.RestTemplate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

public class MusicScraperUtil {

    // Spring Boot 内置的 HTTP 请求工具
    private static final RestTemplate restTemplate = new RestTemplate();

    /**
     * 核心刮削方法：根据歌名和歌手去苹果数据库搜拉取数据
     */
    public static ScrapedData scrapeFromApple(String songName, String singer) {
        try {
            // 如果名字或歌手为空，没法搜，直接放弃
            if (songName == null || songName.isEmpty()) return null;

            String cleanSongName = songName.replaceAll("\\(.*?\\)", "").replaceAll("\\[.*?\\]", "").trim();
            String cleanSinger = (singer != null) ? singer.trim() : "";

            // 拼凑搜索词，比如 "晴天 周杰伦"，并进行 URL 编码防止乱码
            String keyword = cleanSongName + " " + cleanSinger;
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8.toString());

            // 苹果 iTunes 免费搜歌 API (limit=1 表示只要第一条最匹配的结果)
            String url = "https://itunes.apple.com/search?term=" + encodedKeyword + "&entity=song&limit=1";

            // 发起请求！
            String response = restTemplate.getForObject(url, String.class);

            // 解析 JSON
            JSONObject jsonObject = JSON.parseObject(response);
            int resultCount = jsonObject.getIntValue("resultCount");

            if (resultCount > 0) {
                // 拿到了数据！提取第一首歌
                JSONObject track = jsonObject.getJSONArray("results").getJSONObject(0);

                ScrapedData data = new ScrapedData();
                data.setAlbum(track.getString("collectionName")); // 获取官方专辑名

                // 获取发行时间 (原来是 "2003-07-29T07:00:00Z"，我们只要前面的日期)
                String releaseDate = track.getString("releaseDate");
                if (releaseDate != null && releaseDate.length() >= 10) {
                    data.setReleaseTime(releaseDate.substring(0, 10));
                }

                // 重点：获取封面！苹果默认给的是 100x100 的小图，我们用字符串替换强行要 600x600 的超清原图
                String artworkUrl = track.getString("artworkUrl100");
                if (artworkUrl != null) {
                    artworkUrl = artworkUrl.replace("100x100bb", "600x600bb");
                    data.setCoverUrl(artworkUrl);
                }

                System.out.println("✅ 刮削神功生效！找到歌曲: " + track.getString("trackName") + " - " + track.getString("artistName"));
                return data;
            }
        } catch (Exception e) {
            System.err.println("❌ 刮削失败: " + e.getMessage());
        }
        return null;
    }

    // 内部类：用来装刮削回来的宝贝数据
    public static class ScrapedData {
        private String album;
        private String releaseTime;
        private String coverUrl;

        // Getters & Setters
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public String getReleaseTime() { return releaseTime; }
        public void setReleaseTime(String releaseTime) { this.releaseTime = releaseTime; }
        public String getCoverUrl() { return coverUrl; }
        public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    }


    /**
     * 去开源歌词库 Lrclib 抓取滚动歌词
     * @param songName 歌名
     * @param singer 歌手名
     * @return 返回带 [00:00.00] 时间轴的歌词字符串，如果没有则返回 null
     */
    /**
     * 去开源歌词库 Lrclib 抓取滚动歌词
     * @param songName 歌名
     * @param singer 歌手名
     * @return 返回带 [00:00.00] 时间轴的歌词字符串，如果没有则返回 null
     */
    public static String scrapeLyrics(String songName, String singer) {
        try {
            System.out.println("====== 开始抓取歌词 ======");
            if (songName == null || songName.isEmpty()) return null;

            String cleanSongName = songName.replaceAll("\\(.*?\\)", "").replaceAll("\\[.*?\\]", "").trim();
            String cleanSinger = (singer != null) ? singer.trim() : "";

            // ====== 第一次尝试：精准搜索（歌名 + 歌手） ======
            String keyword = cleanSongName + " " + cleanSinger;
            String encodedKeyword = java.net.URLEncoder.encode(keyword.trim(), "UTF-8");
            String url = "https://lrclib.net/api/search?q=" + encodedKeyword;

            System.out.println("🔍 尝试精准搜索: " + url);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "VibeMusicApp/1.0.0 (https://github.com/vibe-music)");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);

            org.springframework.http.ResponseEntity<String> responseEntity =
                    restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);

            JSONArray jsonArray = JSON.parseArray(responseEntity.getBody());

            // ====== 降级策略：如果精准搜索失败，去掉歌手只搜歌名！ ======
            if (jsonArray == null || jsonArray.isEmpty()) {
                System.out.println("⚠️ 精准搜索未找到，尝试仅使用歌名进行宽泛搜索...");
                encodedKeyword = java.net.URLEncoder.encode(cleanSongName, "UTF-8");
                url = "https://lrclib.net/api/search?q=" + encodedKeyword;
                System.out.println("🔍 尝试宽泛搜索: " + url);

                responseEntity = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);
                jsonArray = JSON.parseArray(responseEntity.getBody());
            }

            // ====== 开始解析歌词 ======
            if (jsonArray != null && !jsonArray.isEmpty()) {
                // 优先找带有滚动歌词的
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject result = jsonArray.getJSONObject(i);
                    String syncedLyrics = result.getString("syncedLyrics");
                    if (syncedLyrics != null && !syncedLyrics.trim().isEmpty()) {
                        System.out.println("✅ 完美抓取到滚动歌词：" + cleanSongName);
                        return syncedLyrics;
                    }
                }
                // 退而求其次找静态歌词
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject result = jsonArray.getJSONObject(i);
                    String plainLyrics = result.getString("plainLyrics");
                    if (plainLyrics != null && !plainLyrics.trim().isEmpty()) {
                        System.out.println("⚠️ 拿到静态歌词：" + cleanSongName);
                        return plainLyrics;
                    }
                }
            } else {
                System.out.println("❌ 宽泛搜索也未找到结果，该歌曲真的没有歌词！");
            }
        } catch (Exception e) {
            System.err.println("❌ 歌词抓取发生异常: " + e.getMessage());
        }
        System.out.println("====== 抓取歌词结束 ======");
        return null;
    }


}
