package com.cb.file.service;

/**
 * @author Ran Zhang
 * @since 2024/3/25
 */
public class Test {
    public static void f() {
        String[] a = new String[2];
        Object[] b = a;
        a[0] = "hi";
        b[1] = 42;
    }

    public static void main(String[] args) {
        f();
    }
}
