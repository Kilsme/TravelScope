package com.travelscope.service;

import com.travelscope.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * MinIO 对象存储服务（FR-A03 文档上传存储）
 * <p>
 * MinioClient Bean 初始化（endpoint/access-key/secret-key/bucket 来自
 * AppProperties.MinioConfig）+ bucket 自动创建（幂等）+ upload。
 * </p>
 */
@Service
public class MinioService {

    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioService(AppProperties appProperties) {
        AppProperties.MinioConfig config = appProperties.getMinio();
        this.bucketName = config.getBucketName();
        this.minioClient = MinioClient.builder()
                .endpoint(config.getEndpoint())
                .credentials(config.getAccessKey(), config.getSecretKey())
                .build();
        ensureBucket();
    }

    /**
     * 上传文件
     *
     * @param objectName  对象名（含路径前缀，如 "docs/2026-09-19/uuid.pdf"）
     * @param inputStream 文件流
     * @param size        文件大小
     * @param contentType MIME 类型
     */
    public void upload(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            log.info("MinIO 上传成功: bucket={} object={} size={}", bucketName, objectName, size);
        } catch (MinioException e) {
            log.error("MinIO 上传失败: object={}", objectName, e);
            throw new IllegalStateException("MinIO 上传失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("MinIO 上传异常: object={}", objectName, e);
            throw new IllegalStateException("MinIO 上传异常: " + e.getMessage(), e);
        }
    }

    /**
     * 幂等创建 bucket（启动时执行一次）
     */
    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("MinIO bucket 已创建: {}", bucketName);
            } else {
                log.info("MinIO bucket 已存在: {}", bucketName);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket 检查/创建失败（上传时将报错）: {}", e.getMessage());
        }
    }
}
