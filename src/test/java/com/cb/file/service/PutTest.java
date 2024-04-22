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

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = FileApplication.class)
@EnableAutoConfiguration
public class PutTest {
    @Autowired
    Bucket defaultBucket;

    @Test
    public void test() throws Exception {
        String path = "C:\\documents\\codebench\\file-server\\src\\test\\java\\com\\cb\\file\\service\\javafx-src.zip";
        putObject(defaultBucket, "javafx-src.zip", path);
    }


    /**
     * Put a file to the bucket.
     *
     * @param bucket    bucket
     * @param objectKey looks like this: "folder/fileName".<br/>
     *                  If objectKey = "fileName", we will put the object into the bucket's root.
     * @param filePath  local file path
     * @throws FileNotFoundException if file does not exist, the exception will occurred.
     */
    private void putObject(Bucket bucket, String objectKey, String filePath) throws FileNotFoundException {
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory())
            throw new FileNotFoundException("File does not exist or it is a directory.");

        Bucket.PutObjectInput input = new Bucket.PutObjectInput();
        input.setBodyInputFile(file);
        try {
            Bucket.PutObjectOutput output = bucket.putObject(objectKey, input);
            if (output.getStatueCode() == 201) {
                System.out.println("PUT Object OK.");
                System.out.println("key = " + objectKey);
                System.out.println("path = " + filePath);
            } else {
                // Failed
                System.out.println("Failed to PUT object.");
                System.out.println("key = " + objectKey);
                System.out.println("StatueCode = " + output.getStatueCode());
                System.out.println("Message = " + output.getMessage());
                System.out.println("RequestId = " + output.getRequestId());
                System.out.println("Code = " + output.getCode());
                System.out.println("Url = " + output.getUrl());
            }
        } catch (QSException e) {
            e.printStackTrace();
        }

    }


}
