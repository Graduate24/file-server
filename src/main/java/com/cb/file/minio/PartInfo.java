package com.cb.file.minio;

import io.minio.messages.Part;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * @author Ran Zhang
 * @since 2021/4/25
 */
@Data
public class PartInfo {
    private String etags;

    public PartInfo() {
    }

    public PartInfo(String etags) {
        this.etags = etags;
    }

    public Part[] toParts() {
        if (StringUtils.isEmpty(etags)) {
            return null;
        }
        String[] s = etags.split(",");
        Part[] parts = new Part[s.length];
        for (int i = 0; i < s.length; i++) {
            parts[i] = new Part(i + 1, s[i]);
        }
        return parts;
    }
}
