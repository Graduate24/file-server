package com.cb.file.util;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
public interface Errors {
    /**
     * 错误码
     *
     * @return
     */
    String getCode();

    /**
     * 错误提示
     *
     * @return
     */
    String getName();
}
