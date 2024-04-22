package com.cb.file.service.impl;

import com.cb.file.config.MinioConfig;
import com.cb.file.entity.FileOpResponse;
import com.cb.file.minio.MyClient;
import com.cb.file.minio.PartInfo;
import com.cb.file.service.FileService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedInputStream;
import java.io.InputStream;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Service("minio")
public class MinioFileServiceImpl implements FileService {
    private Logger log = LoggerFactory.getLogger(MinioFileServiceImpl.class);
    @Resource(name = "minioClient")
    private MyClient minioClient;

    @Resource(name = "maskClient")
    private MyClient maskClient;

    @Resource
    MinioConfig config;


    @Override
    public FileOpResponse put(InputStream inputStream, String objectKey, long size) throws Exception {
        FileOpResponse response;
        try {
            minioClient.putObject(
                    PutObjectArgs.builder().bucket(config.getBucketName()).object(objectKey).stream(
                            new BufferedInputStream(inputStream), size, -1).build());
            response = new FileOpResponse(true, 200, "200",
                    "", "", "", objectKey);
        } catch (Exception e) {
            response = new FileOpResponse(false, 500, "500",
                    e.getMessage(), "", "", objectKey);
        }

        log.info("minio PUT Object. response: {}", response.toString());
        return response;
    }

    @Override
    public String init(String objectKey) throws Exception {
        String uploadId =
                minioClient.createMultipartUpload(
                        config.getBucketName(), null, objectKey, null, null);
        log.info("minio init uploadId:{}", uploadId);
        return uploadId;
    }

    @Override
    public FileOpResponse part(InputStream inputStream, String objectKey, String uploadId, Integer partNumber, int size) throws Exception {
        FileOpResponse response;

        try {
            String etag =
                    minioClient.uploadPart(
                            config.getBucketName(),
                            objectKey,
                            new BufferedInputStream(inputStream), size,
                            uploadId,
                            partNumber,
                            null);
            response = new FileOpResponse(true, 200, "200",
                    "", "", uploadId, objectKey, etag);

        } catch (Exception e) {
            log.info("-----minio part error", e);
            response = new FileOpResponse(false, 500, "500",
                    e.getMessage(), "", uploadId, objectKey, null);


        }
        log.info("minio part put Object. response: {}", response.toString());
        return response;


    }

    @Override
    public FileOpResponse complete(String uploadId, String objectKey, Integer size, String md5, String etags) throws Exception {
        PartInfo partInfo = new PartInfo(etags);

        minioClient.completeMultipartUpload(
                config.getBucketName(), null, objectKey, uploadId, partInfo.toParts(), null, null);
        FileOpResponse response = new FileOpResponse(true, 200, "200",
                "", "", uploadId, objectKey);
        log.info("minio CompleteMultipartUpload . response: {}", response.toString());
        return response;
    }

    private FileOpResponse preview(MyClient client,String objectKey, long expTime)throws Exception{
        String url =
                client.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(config.getBucketName())
                                .object(objectKey)
                                .expiry((int) (expTime - System.currentTimeMillis() / 1000))
                                .build());

        FileOpResponse response = new FileOpResponse(true, null, null,
                null, url, null, objectKey);
        log.info("minio preview url. response: {}", response.toString());
        return response;
    }

    @Override
    public FileOpResponse preview(String objectKey, long expTime) throws Exception {
       return preview(maskClient,objectKey,expTime);
    }

    @Override
    public FileOpResponse previewInternal(String objectKey, long expTime) throws Exception {
        return preview(minioClient,objectKey,expTime);
    }

    @Override
    public FileOpResponse download(String objectKey, String fileName, long expTime) throws Exception {
        return preview(objectKey, expTime);
    }

    @Override
    public FileOpResponse downloadInternal(String objectKey, String fileName, long expTime) throws Exception {
        return previewInternal(objectKey, expTime);
    }

    @Override
    public FileOpResponse delete(String objectKey) throws Exception {
        minioClient.removeObject(
                RemoveObjectArgs.builder().bucket(config.getBucketName()).object(objectKey).build());
        FileOpResponse response = new FileOpResponse(true, 200, "200",
                "", "", "", objectKey);
        log.info("minio delete object . response: {}", response.toString());
        return response;
    }
}
