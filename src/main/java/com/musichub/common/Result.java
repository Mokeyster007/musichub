package com.musichub.common;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    // 成功静态方法（无数据）
    public static Result success() {
        Result result = new Result();
        result.setCode(200);
        result.setMessage("操作成功");
        return result;
    }

    // 成功静态方法（有数据）
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    // 失败静态方法
    public static Result error(String message) {
        Result result = new Result();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }

    // 自定义状态码
    public static Result error(Integer code, String message) {
        Result result = new Result();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static Result fail(String message) {
        Result result = new Result();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }

    // 成功静态方法（自定义消息 + 数据）
    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage(message);
        result.setData(data);
        return result;
    }
}
