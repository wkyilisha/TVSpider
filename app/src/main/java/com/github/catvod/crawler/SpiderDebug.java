package com.github.catvod.crawler;

import android.util.Log;

public class SpiderDebug {
    private static final String TAG = "SpiderDebug";

    // 1. 打印通用日志
    public static void log(String msg) {
        Log.d(TAG, "[爬虫记录] " + msg);
    }

    // 2. 专门打印“步骤”和“关键数据”
    // 调用示例：SpiderDebug.step("分类解析", "拿到的分类总数: 15");
    public static void step(String stage, String data) {
        String output = String.format("\n📍【步骤: %s】\n内容: %s\n--------------------", stage, data);
        Log.i(TAG, output); // 使用 Log.i 方便在网页中区分
    }

    // 3. 打印报错
    public static void log(Throwable e) {
        Log.e(TAG, "❌ 运行中断: " + e.getMessage());
    }
}
