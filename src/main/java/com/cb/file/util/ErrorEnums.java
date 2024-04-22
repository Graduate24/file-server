package com.cb.file.util;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
public enum ErrorEnums implements Errors {

    /***********系统异常************/
    SERVER_ERR0R("500", "服务异常"),
    REPEAT_SUBMIT_ERR0R("505", "重复提交"),

    /***********FILE************/
    FILE_EMPTY("1001", "文件为空"),
    PUT_MAX_SIZE("1002", "单个上传文件尺寸不能超过30M"),
    PART_MAX_SIZE("1003", "分段上传文件每个尺寸不能超过10M");

    private String code;
    private String name;

    ErrorEnums(String code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return code;
    }
}
