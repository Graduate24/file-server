package com.cb.file.service;

import com.cb.file.FileApplication;
import com.qingstor.sdk.exception.QSException;
import com.qingstor.sdk.service.Bucket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Date;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = FileApplication.class)
@EnableAutoConfiguration
public class DownloadTest {
    @Autowired
    Bucket defaultBucket;

    @Test
    public void test() throws Exception {
        long expiresTime = new Date().getTime() / 1000 + 60 * 10; // 600秒（10分钟）后过期
        String objectUrl = defaultBucket.GetObjectSignatureUrl("common\\5ece59a911e958881bbd7840\\03438_archesnationalpark_5120x3200.jpg", expiresTime);
        System.out.println(objectUrl);
    }


}
