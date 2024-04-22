package com.cb.file.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import static com.cb.file.util.ErrorEnums.SERVER_ERR0R;
import static com.cb.file.util.HttpResponse.error;


/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@ControllerAdvice
@Slf4j
public class ExceptionAdvice {

    /**
     * 全局异常捕捉处理
     *
     * @param ex
     * @return
     */
    @ResponseBody
    @ExceptionHandler(value = Exception.class)
    public HttpResponse errorHandler(Exception ex) {
        log.error("global error", ex);
        return HttpResponse.error(ex);
    }

}
