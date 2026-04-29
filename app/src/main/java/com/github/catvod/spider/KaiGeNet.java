package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KaiGeNet {

    // 🚀 Cookie 緩存：解決「二次請求」和「登錄狀態」的核心
    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    /**
     * 凱哥智慧請求核心
     * @param siteUrl 來源站點（用於注入 Referer）
     * @param method 請求方式 get/post
     * @param url 目標網址
     * @param body 請求參數
     * @param headers 自定義頭
     */
    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        if (headers == null) headers = new HashMap<>();

        // 1. 注入萬用 UA
        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", MOBILE_UA);

        // 2. 🚀 凱哥防護：注入安全 Referer (過濾非 ASCII 字符，防止中文路徑崩潰)
        if (!headers.containsKey("Referer")) {
            if (!TextUtils.isEmpty(siteUrl) && siteUrl.matches("^[\\x00-\\x7F]*$")) {
                headers.put("Referer", siteUrl);
            } else {
                // 如果路徑有中文，則降級使用該站點的 Host 域名
                headers.put("Referer", getHost(siteUrl) + "/");
            }
        }

        // 3. 自動注入該站點之前的歷史 Cookie
        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        // 4. 執行第一次請求
        OkResult res = execute(method, url, body, headers);

        // 5. 🚀 核心提取：從響應頭拿到新的 Set-Cookie
        String setCookie = getSetCookie(res.getResp());

        if (!TextUtils.isEmpty(setCookie)) {
            cookieJar.put(host, setCookie);
            
            // 🚀 凱哥特技：自動補刀 (解決 5s 盾、防火牆或 Cookie 驗證頁面)
            // 如果返回內容太短，說明還沒進到正題，帶著新 Cookie 立刻再請求一次
            if (res.getBody().trim().length() < 1000) {
                headers.put("Cookie", setCookie);
                res = execute(method, url, body, headers);
                
                // 二次請求後再次同步最新 Cookie
                String secondCookie = getSetCookie(res.getResp());
                if (!TextUtils.isEmpty(secondCookie)) cookieJar.put(host, secondCookie);
            }
        }

        return res;
    }

    // 🚀 內部執行器：支持 POST(JSON/表單) 和 GET 參數自動轉換
    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        
        if ("post".equals(method)) {
            // 如果 body 是 JSON 字符串則直接 POST 字符串，否則轉 Map 發送表單
            if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                return OkHttp.post(url, body, headers);
            } else {
                return OkHttp.post(url, parseToMap(body), headers);
            }
        }
        
        // 默認使用 GET
        return OkHttp.get(url, parseToMap(body), headers);
    }

    // 輔助：從 OkResult 的響應頭中安全提取 Cookie 字符串
    private static String getSetCookie(Map<String, List<String>> respHeaders) {
        if (respHeaders == null) return "";
        List<String> cookies = respHeaders.get("Set-Cookie");
        if (cookies == null) cookies = respHeaders.get("set-cookie");
        if (cookies != null && !cookies.isEmpty()) {
            return TextUtils.join(";", cookies);
        }
        return "";
    }

    // 輔助：提取網址 Host 域名（帶層級兼容）
    private static String getHost(String urlStr) {
        if (TextUtils.isEmpty(urlStr)) return "";
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            try {
                return java.net.URI.create(urlStr).getHost();
            } catch (Exception ex) {
                return urlStr;
            }
        }
    }

    // 輔助：將 URL 參數字符串轉為 Map
    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {}
        return map;
    }
}
