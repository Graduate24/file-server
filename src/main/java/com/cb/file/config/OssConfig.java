package com.cb.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Component
@ConfigurationProperties(prefix = "oss")
@Data
public class OssConfig {

    public String service = "minio";


}
