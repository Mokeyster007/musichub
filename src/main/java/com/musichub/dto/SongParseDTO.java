package com.musichub.dto;


import lombok.Data;

@Data
public class SongParseDTO {
    private String name;        // 歌曲名
    private String singer;      // 歌手名
    private String album;       // 专辑名
    private String duration;    // 时长 (可选)
    private String songUrl;     // 音频文件在 MinIO 上的临时/永久地址
    private String pic;         // 解析出的封面在 MinIO 上的地址
    private String lyric;       // 解析对应歌词
}
