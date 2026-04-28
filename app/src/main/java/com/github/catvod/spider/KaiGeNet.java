package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凱哥專用：自動化網絡請求引擎
 * 功能：自動管理Cookie、手機UA偽裝、請求重試、智能Body轉換
 */
public class KaiGeNet {

    // 🚀 全局 Cookie 緩存罐，按域名(Host)隔離存儲，解決登錄態和安全驗證問題
    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();

    // 📱 強力手機版 UA，繞過大多數網站的電腦端攔截和跳轉
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    /**
     * 核心請求入口
     * @param siteUrl 站點主頁（用於 Referer 保底）
     * @param method  請求方式：get 或 post
     * @param url     目標網址
     * @param body    請求體（a=1&b=2 或 {"a":1}）
     * @param headers 自定義頭（可為空）
     */
    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        
        // 1. 初始化並補強 Header
        if (headers == null) headers = new HashMap<>();
        if (!headers.containsKey("User-Agent")) {
            headers.put("User-Agent", MOBILE_UA);
        }
        if (!headers.containsKey("Referer")) {
            headers.put("Referer", siteUrl);
        }
        if (!headers.containsKey("X-Requested-With")) {
            headers.put("X-Requested-With", "com.android.browser"); // 偽裝原生瀏覽器
        }

        // 2. 自動注入該站點歷史 Cookie
        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        // 3. 執行第一次請求
        OkResult res = execute(method, url, body, headers);

        // 4. 處理 Cookie 持久化與「二次請求補刀」
        String setCookie = res.getHeader("Set-Cookie");
        if (!TextUtils.isEmpty(setCookie)) {
            // 更新緩存罐裡的 Cookie
            cookieJar.put(host, setCookie);
            
            // 🚀 【關鍵補刀】：如果返回了新 Cookie 但內容長度不足（可能是中轉頁/驗證頁）
            // 立即帶著新身份重新請求一次
            if (res.getBody().trim().length() < 1000) {
                headers.put("Cookie", setCookie);
                res = execute(method, url, body, headers);
            }
        }

        return res;
    }

    /**
     * 底層執行：處理 POST 格式轉換與 GET 發起
     */
    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        
        if ("post".equals(method)) {
            // 📦 智能 Body 解析：如果是 JSON 則發送 JSON，否則發送 Form 表單
            if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                return OkHttp.postJson(url, body, headers);
            } else {
                return OkHttp.post(url, parseToMap(body), headers);
            }
        }
        
        // 默認 GET 請求
        return OkHttp.get(url, null, headers);
    }

    /**
     * 將 a=1&b=2 格式的字符串轉為 Map
     */
    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0], kv[1]);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    /**
     * 獲取網址的 Host 用作 Cookie 隔離的 Key
     */
    private static String getHost(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return urlStr;
        }
    }

    /**
     * 清除特定站點的 Cookie（可選功能，用於調試）
     */
    public static void clearCookie(String url) {
        cookieJar.remove(getHost(url));
    }
}
