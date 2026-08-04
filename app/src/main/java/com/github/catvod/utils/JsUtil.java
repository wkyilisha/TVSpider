package com.github.catvod.utils;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

public class JsUtil {

    /**
     * 执行 JS 动态算号/解密
     * @param jsCode JS 源码
     * @param functionCall 函数调用语句，例如 "decrypt('xxxx')"
     * @return 执行结果
     */
    public static String execJs(String jsCode, String functionCall) {
        Context cx = Context.enter();
        // 关闭编译优化，适配 Android DEX 运行环境
        cx.setOptimizationLevel(-1);
        try {
            Scriptable scope = cx.initStandardObjects();
            // 1. 执行全局 JS 代码
            cx.evaluateString(scope, jsCode, "script", 1, null);
            // 2. 执行目标解密函数
            Object result = cx.evaluateString(scope, functionCall, "call", 1, null);
            return Context.toString(result);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        } finally {
            Context.exit();
        }
    }
}