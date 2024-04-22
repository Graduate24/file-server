package com.cb.file.entity;

import lombok.Data;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Data
public class Fastdfsfile {
    private String name;
    private byte[] content;
    private String ext;
    private String md5;
    private String author;

    public Fastdfsfile() {
    }

    public Fastdfsfile(String name, byte[] content, String ext) {
        this.name = name;
        this.content = content;
        this.ext = ext;
    }
}
