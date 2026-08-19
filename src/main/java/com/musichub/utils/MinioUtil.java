package com.musichub.utils;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Component
public class MinioUtil {

    @Autowired
    private MinioClient minioClient;

    // 你的桶名
    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    // 你的 MinIO 访问地址
    @Value("${minio.bucket-name:vibe-music-data}")
    private String bucketName;

    /**
     * 上传文件到 MinIO
     * @param file 前端传来的文件
     * @param folderName 文件夹名称，例如 "users/"、"songs/"、"songCovers/"
     * @return 返回文件可访问的完整 URL
     */
    public String upload(MultipartFile file, String folderName) throws Exception {
        // 1. 获取原始文件名
        String originalFilename = file.getOriginalFilename();

        // 2. 提取后缀
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        // 3. 生成唯一文件名
        String newFileName = UUID.randomUUID().toString() + suffix;

        // 4. 防止 folderName 没有 /
        if (folderName != null && !folderName.endsWith("/")) {
            folderName += "/";
        }

        // 5. 最终对象名
        String objectName = (folderName == null ? "" : folderName) + newFileName;

        // 6. 上传到 MinIO
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }

        // 7. 返回可访问 URL
        return endpoint + "/" + bucketName + "/" + objectName;
    }

    /**
     * 删除 MinIO 中的旧文件
     * @param fileUrl 数据库中保存的完整 URL
     */
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) {
            return;
        }

        try {
            String bucketPrefix = bucketName + "/";
            int index = fileUrl.indexOf(bucketPrefix);

            if (index != -1) {
                String objectName = fileUrl.substring(index + bucketPrefix.length());

                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build()
                );
                System.out.println("成功删除旧文件: " + objectName);
            }
        } catch (Exception e) {
            System.err.println("删除旧文件失败: " + e.getMessage());
        }
    }
}
