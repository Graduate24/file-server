package com.cb.file.util;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@FunctionalInterface
public interface RequestCheck {

    String requestId = null;

    /**
     * @return
     */
    default boolean isChecked() {
        return getError() == null;
    }


    /**
     * @return
     */
    ErrorEnums getError();
}
