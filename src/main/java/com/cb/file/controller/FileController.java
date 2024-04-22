package com.cb.file.controller;


import com.cb.file.config.OssConfig;
import com.cb.file.entity.FileOpResponse;
import com.cb.file.service.FileService;
import com.cb.file.util.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static com.cb.file.util.ErrorEnums.*;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@RestController
public class FileController {

    Logger log = LoggerFactory.getLogger(FileController.class);
    @Resource
    OssConfig config;

    @Resource
    private Map<String, FileService> fileService;

    @Value("${upload.put-max-size}")
    private Long maxPutSize;

    @Value("${upload.part-max-size}")
    private Long maxPartSize;


    /**
     * @param file      file
     * @param objectKey looks like this: "folder/fileName".
     *                  If objectKey = "fileName", we will put the object into the bucket's root.
     * @return
     * @throws Exception
     */
    @PostMapping("/upload")
    public HttpResponse<FileOpResponse> upload(@RequestParam MultipartFile file,
                                               @RequestParam String objectKey) throws Exception {
        log.info("upload,contentType:{}, original name:{}, objectKey:{}, size:{}",
                file.getContentType(), file.getOriginalFilename(), objectKey, file.getSize());

        if (file.isEmpty()) {
            return HttpResponse.error(FILE_EMPTY);
        }
        if (file.getSize() > maxPutSize * 1024 * 1024) {
            return HttpResponse.error(PUT_MAX_SIZE);
        }
        FileOpResponse response = fileService.get(config.service).put(file.getInputStream(), objectKey, file.getSize());
        return HttpResponse.success(response);
    }


    /**
     * @param objectKey
     * @return
     * @throws Exception
     */
    @PostMapping("/part/init")
    public HttpResponse<String> multiUploadInit(@RequestParam String objectKey) throws Exception {
        log.info("Multi Upload part Init ,objectKey:{}", objectKey);
        String uploadId = fileService.get(config.service).init(objectKey);
        log.info("Multi UploadId :{}", uploadId);
        return HttpResponse.success(uploadId);
    }

    /**
     * @param file
     * @param uploadId
     * @return
     * @throws Exception
     */
    @PostMapping("/part")
    public HttpResponse<FileOpResponse> multiUpload(@RequestParam MultipartFile file,
                                                    @RequestParam String uploadId,
                                                    @RequestParam String objectKey,
                                                    @RequestParam Integer partNumber) throws Exception {
        log.info("Multi Upload part ,contentType:{}, original name:{}, uploadId:{},partNumber:{}size:{}",
                file.getContentType(), file.getOriginalFilename(), uploadId, partNumber, file.getSize());

        if (file.isEmpty()) {
            return HttpResponse.error(FILE_EMPTY);
        }
        if (file.getSize() > maxPartSize * 1024 * 1024) {
            return HttpResponse.error(PART_MAX_SIZE);
        }

        FileOpResponse response = fileService.get(config.service).part(file.getInputStream(), objectKey, uploadId,
                partNumber, (int) file.getSize());
        return HttpResponse.success(response);
    }

    @PostMapping("/part/complete")
    public HttpResponse<FileOpResponse> multiUploadComplete(@RequestParam String uploadId,
                                                            @RequestParam String objectKey,
                                                            @RequestParam Integer size,
                                                            @RequestParam String md5,
                                                            @RequestParam Optional<String> etags) throws Exception {
        log.info("Multi Upload complete ,uploadId:{} , objectKey:{}, size:{},md5:{}", uploadId, objectKey, size, md5);
        FileOpResponse response = fileService.get(config.service).complete(uploadId, objectKey, size, md5, etags.orElse(null));
        return HttpResponse.success(response);
    }

    @PostMapping("/preview")
    public HttpResponse<FileOpResponse> getSignatureUrl(@RequestParam String objectKey,
                                                        @RequestParam Optional<Long> expiresTime) throws Exception {
        long exp = expiresTime.orElse(System.currentTimeMillis() / 1000 + 60 * 60 * 24);
        log.info("get preview url, objectKey:{} , expiresTime:{} ", objectKey, exp);
        FileOpResponse response = fileService.get(config.service).preview(objectKey, exp);
        return HttpResponse.success(response);
    }

    @PostMapping("/download")
    public HttpResponse<FileOpResponse> getDownloadUrl(@RequestParam String objectKey,
                                                       @RequestParam String fileName,
                                                       @RequestParam Optional<Boolean> internal,
                                                       @RequestParam Optional<Long> expiresTime) throws Exception {
        long exp = expiresTime.orElse(System.currentTimeMillis() / 1000 + 60 * 60 * 24);
        boolean isInternal = internal.orElse(false);
        log.info("get download url, objectKey:{} , fileName:{}, expiresTime:{} ", objectKey, fileName, exp);
        FileOpResponse response = isInternal ? fileService.get(config.service).downloadInternal(objectKey, fileName, exp) :
                fileService.get(config.service).download(objectKey, fileName, exp);
        return HttpResponse.success(response);
    }

    @PostMapping("/delete")
    public HttpResponse<FileOpResponse> delete(@RequestParam String objectKey) throws Exception {
        log.info("delete, objectKey:{} ", objectKey);
        FileOpResponse response = fileService.get(config.service).delete(objectKey);
        return HttpResponse.success(response);
    }

    @GetMapping("/localtime")
    public HttpResponse<LocalDateTime> localTime() {
        log.info("time:{}", LocalDateTime.now());
        return HttpResponse.success(LocalDateTime.now());
    }
}
