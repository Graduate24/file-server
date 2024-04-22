package com.cb.file.service;

import com.cb.file.FileApplication;
import com.qingstor.sdk.exception.QSException;
import com.qingstor.sdk.service.Bucket;
import com.qingstor.sdk.service.QingStor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = FileApplication.class)
@EnableAutoConfiguration
public class FileServiceTest {
    @Autowired
    FileService fileService;


}
