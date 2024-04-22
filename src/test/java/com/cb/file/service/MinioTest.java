package com.cb.file.service;

import com.cb.file.minio.MyClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.PutObjectArgs;
import io.minio.errors.*;
import io.minio.http.Method;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
public class MinioTest {
    private MyClient minioClient;

    @Before
    public void setUp() throws Exception {
        minioClient =
                MyClient.builder()
                        .endpoint("http://139.198.21.172:9010")
                        .credentials("minio", "minio123")
                        .build();
    }

    @Test
    public void test() throws Exception {
        // Create object ends with '/' (also called as folder or directory).
        File f = new File("/Users/zhangran/Downloads/P0262.png");

        FileInputStream fis = new FileInputStream(f);

        minioClient.putObject(
                PutObjectArgs.builder().bucket("test000").object("img/P0262.png").stream(
                        fis, f.length(), -1).contentType("image/png")
                        .build());
    }

    @Test
    public void test1() throws IOException, InvalidKeyException, InvalidResponseException, InsufficientDataException, InvalidExpiresRangeException, ServerException, InternalException, NoSuchAlgorithmException, XmlParserException, InvalidBucketNameException, ErrorResponseException {
        String url =
                minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("test000")
                                .object("img/P0262.png")
                                .expiry(24 * 60 * 60)
                                .build());


        System.out.println(url);
    }

    @Test
    public void testPutPart() throws Exception {
        //块文件目录
        String chunkFileFolderPath = "/Users/zhangran/Documents/cb/file-server/f/";
        //块文件目录对象
        File chunkFileFolder = new File(chunkFileFolderPath);
        //块文件列表
        File[] files = chunkFileFolder.listFiles();
        //将块文件排序，按名称升序
        List<File> fileList = Arrays.asList(files);
        fileList.sort((o1, o2) -> {
            if (Integer.parseInt(o1.getName()) > Integer.parseInt(o2.getName())) {
                return 1;
            }
            return -1;

        });
        File o = new File("/Users/zhangran/Downloads/P0262.png");
        for (File f : fileList) {
            FileInputStream fis = new FileInputStream(f);

            minioClient.putObject(
                    PutObjectArgs.builder().bucket("test000").object("test/P0060.png").stream(
                            fis, -1, f.length())
                            .build());
            System.out.println(fileList);
        }

    }
}
