package com.cb.file.entity;

import lombok.Data;
import lombok.ToString;


/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Data
@ToString
public class FileOpResponse {
    private boolean success;
    private String requestId;
    private String message;
    private Integer statusCode;
    private String code;
    private String url;
    private String objectKey;
    private String eTag;


    public FileOpResponse() {
    }

    public FileOpResponse(boolean success, Integer statusCode, String code, String message, String url, String requestId, String objectKey) {
        this.success = success;
        this.statusCode = statusCode;
        this.code = code;
        this.message = message;
        this.url = url;
        this.requestId = requestId;
        this.objectKey = objectKey;
    }

    public FileOpResponse(boolean success, Integer statusCode, String code, String message, String url, String requestId, String objectKey, String etag) {
        this.success = success;
        this.statusCode = statusCode;
        this.code = code;
        this.message = message;
        this.url = url;
        this.requestId = requestId;
        this.objectKey = objectKey;
        this.eTag = etag;
    }
}
