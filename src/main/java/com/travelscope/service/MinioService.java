package com.travelscope.service;

import com.travelscope.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
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
                .httpClient(new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .readTimeout(java.time.Duration.ofSeconds(45))
                        .writeTimeout(java.time.Duration.ofSeconds(45))
                        .callTimeout(java.time.Duration.ofSeconds(55)).build())
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
     * 下载文件（P1-L 文档摄入：取回上传原件做解析）
     *
     * @param objectName 对象名（documents.file_path 存的那个）
     * @return 对象流（<b>调用方负责关闭</b>——随 try-with-resources 用）
     */
    public InputStream download(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 下载失败: " + objectName + " - " + e.getMessage(), e);
        }
    }

    public void delete(String objectKey) {
        try { minioClient.removeObject(io.minio.RemoveObjectArgs.builder().bucket(bucketName).object(objectKey).build()); }
        catch(Exception e) { throw new IllegalStateException("图片清理失败",e); }
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
