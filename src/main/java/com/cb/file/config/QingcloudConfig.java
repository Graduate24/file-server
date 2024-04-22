package com.cb.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Component
@ConfigurationProperties(prefix = "qy")
@Data
public class QingcloudConfig {

    private String accessKeyId;
    private String secretAccessKey;
    private String bucketName;
    private String zone;
    private String protocol = "https";
    private String host = "qingstor.com";
    private String port = "";


}
