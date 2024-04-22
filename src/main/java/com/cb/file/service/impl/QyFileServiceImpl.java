package com.cb.file.service.impl;

import com.cb.file.entity.FileOpResponse;
import com.cb.file.service.FileService;
import com.qingstor.sdk.request.RequestHandler;
import com.qingstor.sdk.service.Bucket;
import com.qingstor.sdk.service.QingStor;
import com.qingstor.sdk.utils.QSStringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Service("qingcloud")
public class QyFileServiceImpl implements FileService {

    Logger log = LoggerFactory.getLogger(QyFileServiceImpl.class);
    @Autowired
    private QingStor storeService;

    @Autowired
    private Bucket bucket;

    @Override
    public FileOpResponse put(InputStream inputStream, String objectKey, long size) throws Exception {
        Bucket.PutObjectInput input = new Bucket.PutObjectInput();
        input.setBodyInputStream(inputStream);
        Bucket.PutObjectOutput output = bucket.putObject(objectKey, input);
        FileOpResponse response = new FileOpResponse(output.getStatueCode() == 201, output.getStatueCode(), output.getCode(),
                output.getMessage(), output.getUrl(), output.getRequestId(), objectKey);
        log.info("PUT Object. response: {}", response.toString());
        return response;
    }

    @Override
    public String init(String objectKey) throws Exception {
        Bucket.InitiateMultipartUploadInput inputInit = new Bucket.InitiateMultipartUploadInput();
        Bucket.InitiateMultipartUploadOutput initOutput = bucket.initiateMultipartUpload(objectKey, inputInit);
        return initOutput.getUploadID();
    }

    @Override
    public FileOpResponse part(InputStream inputStream, String objectKey, String uploadId, Integer partNumber, int size) throws Exception {
        // 上传分段
        Bucket.UploadMultipartInput input = new Bucket.UploadMultipartInput();
        input.setBodyInputStream(inputStream);
        input.setPartNumber(partNumber - 1);
        input.setUploadID(uploadId);
        // 创建请求上传一个分段.
        Bucket.UploadMultipartOutput output = bucket.uploadMultipart(objectKey, input);
        FileOpResponse response = new FileOpResponse(output.getStatueCode() == 201, output.getStatueCode(), output.getCode(),
                output.getMessage(), output.getUrl(), output.getRequestId(), objectKey);
        log.info("Multipart upload. response: {}", response.toString());
        return response;

    }

    @Override
    public FileOpResponse complete(String uploadId, String objectKey, Integer size, String md5, String etags) throws Exception {
        // 该构造方法将自动设置 upload id 和 body input.
        Bucket.CompleteMultipartUploadInput completeMultipartUploadInput =
                new Bucket.CompleteMultipartUploadInput(uploadId, size, 0);
        completeMultipartUploadInput.setETag(md5);
        Bucket.CompleteMultipartUploadOutput output = bucket.completeMultipartUpload(objectKey, completeMultipartUploadInput);
        FileOpResponse response = new FileOpResponse(output.getStatueCode() == 201, output.getStatueCode(), output.getCode(),
                output.getMessage(), output.getUrl(), output.getRequestId(), objectKey);
        log.info("CompleteMultipartUpload . response: {}", response.toString());
        return response;
    }

    @Override
    public FileOpResponse preview(String objectKey, long expTime) throws Exception {
        String objectUrl = bucket.GetObjectSignatureUrl(objectKey, expTime);
        FileOpResponse response = new FileOpResponse(true, null, null,
                null, objectUrl, null, objectKey);
        log.info("preview url. response: {}", response.toString());
        return response;
    }

    @Override
    public FileOpResponse previewInternal(String objectKey, long expTime) throws Exception {
        return null;
    }

    @Override
    public FileOpResponse download(String objectKey, String fileName, long expTime) throws Exception {
        Bucket.GetObjectInput inputs = new Bucket.GetObjectInput();
        String keyName = QSStringUtil.percentEncode(fileName, "utf-8");
        inputs.setResponseContentDisposition(String.format("attachment; filename=\"%s\"; filename*=utf-8''%s", keyName, keyName));
        RequestHandler handle = bucket.GetObjectBySignatureUrlRequest(objectKey, inputs, expTime);
        String tempUrl = handle.getExpiresRequestUrl();
        FileOpResponse response = new FileOpResponse(true, null, null,
                null, tempUrl, null, objectKey);
        log.info("download url. response: {}", response.toString());
        return response;
    }

    @Override
    public FileOpResponse downloadInternal(String objectKey, String fileName, long expTime) throws Exception {
        return null;
    }

    @Override
    public FileOpResponse delete(String objectKey) throws Exception {
        Bucket.DeleteObjectOutput output = bucket.deleteObject(objectKey);
        FileOpResponse response = new FileOpResponse(output.getStatueCode() == 201, output.getStatueCode(), output.getCode(),
                output.getMessage(), output.getUrl(), output.getRequestId(), objectKey);
        log.info("delete object . response: {}", response.toString());
        return response;
    }
}
