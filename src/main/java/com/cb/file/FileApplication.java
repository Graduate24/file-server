package com.cb.file;

import com.cb.file.config.MinioConfig;
import com.cb.file.config.QingcloudConfig;
import com.cb.file.minio.MyClient;
import com.qingstor.sdk.config.EnvContext;
import com.qingstor.sdk.service.Bucket;
import com.qingstor.sdk.service.QingStor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@SpringBootApplication
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }

    @Bean
    public EnvContext envContext(QingcloudConfig qingcloud) {
        EnvContext context = new EnvContext(qingcloud.getAccessKeyId(), qingcloud.getSecretAccessKey());
        context.setHost(qingcloud.getHost());
        context.setPort(qingcloud.getPort());
        context.setProtocol(qingcloud.getProtocol());
        return context;
    }

    @Bean
    public QingStor storeService(EnvContext envContext) {
        return new QingStor(envContext);
    }

    @Bean
    public Bucket defaultBucket(EnvContext envContext, QingcloudConfig config) {
        return new Bucket(envContext, config.getZone(), config.getBucketName());
    }

    @Bean("minioClient")
    public MyClient myClient(MinioConfig config) {
        return MyClient.builder()
                .endpoint(config.getProtocol() + "://" + config.getHost() +
                        (StringUtils.isEmpty(config.getPort()) ? "" : ":" + config.getPort()))
                .credentials(config.getAccessKeyId(), config.getSecretAccessKey())
                .build();

    }

    @Bean("maskClient")
    public MyClient maskClient(MinioConfig config) {
        String host = StringUtils.isEmpty(config.getMask()) ? config.getHost() : config.getMask();
        return MyClient.builder()
                .endpoint(config.getProtocol() + "://" + host +
                        (StringUtils.isEmpty(config.getPort()) ? "" : ":" + config.getPort()))
                .credentials(config.getAccessKeyId(), config.getSecretAccessKey())
                .build();

    }


}
