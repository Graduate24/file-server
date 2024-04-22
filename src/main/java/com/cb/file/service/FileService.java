package com.cb.file.service;

import com.cb.file.entity.FileOpResponse;

import java.io.InputStream;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
public interface FileService {

    FileOpResponse put(InputStream inputStream, String objectKey, long size) throws Exception;

    String init(String objectKey) throws Exception;

    FileOpResponse part(InputStream inputStream, String objectKey, String uploadId, Integer partNumber, int size) throws Exception;

    FileOpResponse complete(String uploadId, String objectKey, Integer size, String md5, String etags) throws Exception;

    FileOpResponse preview(String objectKey, long expTime) throws Exception;

    FileOpResponse previewInternal(String objectKey, long expTime) throws Exception;

    FileOpResponse download(String objectKey, String fileName, long expTime) throws Exception;

    FileOpResponse downloadInternal(String objectKey, String fileName, long expTime) throws Exception;

    FileOpResponse delete(String objectKey) throws Exception;

}
