package com.cb.file.util;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
@Slf4j
public final class HttpResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private String code;
    private String msg;
    private String subCode;
    private String toast;
    private long timestamp = System.currentTimeMillis();
    private T result;


    public HttpResponse() {

    }


    public HttpResponse(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public HttpResponse(String code, String msg, T result) {
        this.code = code;
        this.msg = msg;
        this.result = result;
    }

    /**
     * 返回错误信息
     *
     * @return
     */
    public static <T> HttpResponse<T> error(String ret, String msg) {
        return new HttpResponse<T>(ret, msg);
    }

    public static <T> HttpResponse<T> error(Errors errors) {
        return new HttpResponse<T>(errors.getCode(), errors.getName());
    }

    public static <T> HttpResponse<T> error(Exception errors) {
        String msg = errors.getMessage();
        return new HttpResponse<T>("500", msg.length() < 200 ? msg : msg.substring(0, 200));
    }

    /**
     * <p>
     * 直接返回成功
     * </p>
     *
     * @return
     */
    public static <T> HttpResponse<T> success() {
        return new HttpResponse<T>(HttpStatus.OK.toString(), HttpStatus.OK.name());
    }

    /**
     * 返回成功信息 此方法将传入参数装到result中，因此只有返回业务数据时调用此方法
     *
     * @return
     */

    public static <T> HttpResponse<T> success(T result) {
        return new HttpResponse<T>(HttpStatus.OK.toString(), HttpStatus.OK.name(), result);
    }


    /**
     * <p>
     * 返回某个参数需要给予别名
     * <li>case:</li>
     * <li>alias=id,content=100000 ,result={"id":100000}</li>
     * </p>
     *
     * @param alias   别名
     * @param content 内容
     * @return
     */
    public static <T> HttpResponse<Map<String, T>> success(String alias, T content) {
        Map<String, T> result = new HashMap<>();
        result.put(alias, content);
        return new HttpResponse<Map<String, T>>(HttpStatus.OK.toString(), HttpStatus.OK.name(),
                result);

    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getResult() {
        return result;
    }

    public void setResult(T result) {
        this.result = result;
    }

}
