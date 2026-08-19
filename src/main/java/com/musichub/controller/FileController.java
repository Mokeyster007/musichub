package com.musichub.controller;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/file")
public class FileController {

    @Autowired
    private MinioClient minioClient;

    // 我们刚刚在网页控制台创建的桶的名字
    private final String BUCKET_NAME = "vibe-music-data";

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return "文件不能为空！";
        }

        try {
            // 1. 获取原始文件名和后缀
            String originalFilename = file.getOriginalFilename();
            String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));

            // 2. 生成一个唯一的文件名
            String newFileName = UUID.randomUUID().toString() + suffix;

            // 3. 把文件以“流”的形式上传到 MinIO
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(newFileName) // 文件在桶里的名字
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType()) // 自动识别是 mp3 还是 jpg
                            .build()
            );

            // 4. 返回可以在浏览器中直接访问的公网绝对地址！
            return "http://localhost:9000/" + BUCKET_NAME + "/" + newFileName;

        } catch (Exception e) {
            e.printStackTrace();
            return "上传失败：" + e.getMessage();
        }
    }
}
