package com.cb.file.service;

import com.cb.file.FileApplication;
import com.cb.file.config.QingcloudConfig;
import com.qingstor.sdk.config.EnvContext;
import com.qingstor.sdk.exception.QSException;
import com.qingstor.sdk.service.Bucket;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.DigestUtils;

import java.io.*;
import java.util.List;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = FileApplication.class)
@EnableAutoConfiguration
public class MuiltPartFile {

    @Autowired
    QingcloudConfig config;

    @Autowired
    EnvContext envContext;

    private String bucketName;
    private String zone;
    private EnvContext ctx;
    private Bucket testBucket;

    private static String apkContentType = "application/vnd.android.package-archive";
    private static Bucket.UploadMultipartOutput uploadMultipartOutput1;
    private static Bucket.UploadMultipartOutput uploadMultipartOutput2;
    private static Bucket.UploadMultipartOutput uploadMultipartOutput3;
    private static Bucket.ListMultipartOutput listMultipartOutput;
    private static Bucket.CompleteMultipartUploadOutput completeMultipartUploadOutput;
    private static Bucket.AbortMultipartUploadOutput abortMultipartUploadOutput;
    private static Bucket.DeleteObjectOutput deleteObjectOutput;

    private static Bucket.InitiateMultipartUploadOutput initOutput;

    private static String multipart_upload_name = "";
    private static String multipart_upload_id = "";


    @Before
    public void setUp() throws Exception {
        bucketName = config.getBucketName();
        zone = config.getZone();
        ctx = envContext;
        testBucket = new Bucket(ctx, zone, bucketName);
    }

    @Test
    public void test() throws Exception {
        upload("en/en.pdf");
    }

    @Test
    public void test2() throws Exception {
        uploadStream("src.zip");
    }

    public void initiate_multipart_upload_with_key(String objectKey) throws Exception {
        Bucket.InitiateMultipartUploadInput input = new Bucket.InitiateMultipartUploadInput();
        initOutput = testBucket.initiateMultipartUpload(objectKey, input);
        multipart_upload_name = objectKey;
        multipart_upload_id = initOutput.getUploadID();
        System.out.println("multipart_upload_id " + multipart_upload_id);
        System.out.println("StatueCode " + initOutput.getStatueCode());

    }

    public void upload(String objectKey) throws QSException, IOException {

        Bucket.InitiateMultipartUploadInput inputInit = new Bucket.InitiateMultipartUploadInput();
        Bucket.InitiateMultipartUploadOutput initOutput = testBucket.initiateMultipartUpload(objectKey, inputInit);

        // 第1步：初始化 multipart_upload_id
        String multipart_upload_id = initOutput.getUploadID();

        System.out.println("-multipart_upload_id----" + initOutput.getUploadID());
        String path = "C:\\documents\\codebench\\file-server\\src\\test\\java\\com\\cb\\file\\service\\stringart.en.zh-CN.pdf";
        File file = new File(path);
        System.out.println(file.exists());
        long contentLength = file.length();
        // 文件分段计数
        int count = 0;
        long partSize = 5 * 1024 * 1024; // Set part size to 5 MB.
        // 第2步：上传分段
        long filePosition = 0;
        for (int i = 0; filePosition < contentLength; i++) {
            // 最后一段可以小于 4 MB. 计算每一段的大小.
            partSize = Math.min(partSize, (contentLength - filePosition));
            Bucket.UploadMultipartInput input = new Bucket.UploadMultipartInput();
            input.setBodyInputFilePart(file);
            input.setFileOffset(filePosition);
            input.setContentLength(partSize);
            input.setPartNumber(i);
            input.setUploadID(multipart_upload_id);

            // 创建请求上传一个分段.
            testBucket.uploadMultipart(objectKey, input);

            filePosition += partSize;
            count++;
        }

        // 第3步: 完成.
        // 该构造方法将自动设置 upload id 和 body input.
        Bucket.CompleteMultipartUploadInput completeMultipartUploadInput =
                new Bucket.CompleteMultipartUploadInput(multipart_upload_id, count, 0);
        // 您可以设置该对象的 MD5 信息.
        completeMultipartUploadInput.setETag(DigestUtils.md5DigestAsHex(new FileInputStream(path)));
        Bucket.CompleteMultipartUploadOutput result = testBucket.completeMultipartUpload(objectKey, completeMultipartUploadInput);
        System.out.println(result.getStatueCode());

    }

    public void uploadStream(String objectKey) throws Exception {

        Bucket.InitiateMultipartUploadInput inputInit = new Bucket.InitiateMultipartUploadInput();

        Bucket.InitiateMultipartUploadOutput initOutput = testBucket.initiateMultipartUpload(objectKey, inputInit);

        // 第1步：初始化 multipart_upload_id
        String multipart_upload_id = initOutput.getUploadID();

        System.out.println("-multipart_upload_id----" + initOutput.getUploadID());

        File f = new File("C:\\documents\\codebench\\file-server\\src\\test\\java\\com\\cb\\file\\service\\src.zip");
        long length = 4 * 1024 * 1024L;// 4MB/part
        if (f.length() < length) length = f.length();
        System.out.println("f.length() = " + f.length());

        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[(int) length];
        System.out.println("buf.length = " + buf.length);
        int len = 0;
        int count = 0;
        try {
            while ((len = fis.read(buf)) != -1) {
                // 第2步：上传分段
                Bucket.UploadMultipartInput input = new Bucket.UploadMultipartInput();
                input.setBodyInputStream(new ByteArrayInputStream(buf.clone(), 0, len));
                // 如果您没有设置 offset, 我们将以 offset = 0 上传该流.
                input.setFileOffset(0L);
                // 如果您没有设置 content length, 我们将上传整个流.
                input.setContentLength((long) len);
                input.setPartNumber(count);
                input.setUploadID(multipart_upload_id);

                // 创建请求上传一个分段.
                testBucket.uploadMultipart(objectKey, input);
                count++;
            }
            fis.close();
            System.out.println(count);
            // 第3步: 完成.
            // 该构造方法将自动设置 upload id 和 body input.
            Bucket.CompleteMultipartUploadInput completeMultipartUploadInput =
                    new Bucket.CompleteMultipartUploadInput(multipart_upload_id, count, 0);
            // 您可以设置该对象的 MD5 信息.
            completeMultipartUploadInput.setETag(DigestUtils.md5DigestAsHex(new FileInputStream(f)));
            testBucket.completeMultipartUpload(objectKey, completeMultipartUploadInput);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void multipartUpload(Bucket bucket, List<File> files, String objectKey) throws QSException {
        if (files == null || files.size() < 1)
            throw new QSException("Files' counts can not be less than one!!");

        String multipart_upload_id = "";

        Bucket.InitiateMultipartUploadInput inputInit = new Bucket.InitiateMultipartUploadInput();

        Bucket.InitiateMultipartUploadOutput initOutput = bucket.initiateMultipartUpload(objectKey, inputInit);
        multipart_upload_id = initOutput.getUploadID();
        System.out.println("-multipart_upload_id----" + initOutput.getUploadID());

        for (int i = 0; i < files.size(); i++) {
            Bucket.UploadMultipartInput input = new Bucket.UploadMultipartInput();
            input.setBodyInputFilePart(files.get(i));
            input.setFileOffset(0L);
            input.setContentLength(files.get(i).length());
            input.setPartNumber(i);
            input.setUploadID(multipart_upload_id);

            Bucket.UploadMultipartOutput bm = bucket.uploadMultipart(objectKey, input);
            System.out.println("-UploadMultipartOutput----" + bm.getMessage());
        }

        // 该构造方法将自动设置 upload id 和 body input.
        Bucket.CompleteMultipartUploadInput completeMultipartUploadInput =
                new Bucket.CompleteMultipartUploadInput(multipart_upload_id, files.size(), 0);
        // 您可以设置该对象的 MD5 信息.
        //completeMultipartUploadInput.setETag("object-MD5");
        bucket.completeMultipartUpload(objectKey, completeMultipartUploadInput);
    }
}
