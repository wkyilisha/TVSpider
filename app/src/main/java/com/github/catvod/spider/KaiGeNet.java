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

    // 🚀 Cookie 緩存：解決「二次請求」的核心
    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        
        if (headers == null) headers = new HashMap<>();
        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", MOBILE_UA);
        if (!headers.containsKey("Referer")) headers.put("Referer", siteUrl);

        // 自動注入歷史 Cookie
        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        // 第一次請求
        OkResult res = execute(method, url, body, headers);

        // 🛡️ 根據 OkResult.java 源碼適配：使用 getResp()
        Map<String, List<String>> respHeaders = res.getResp();
        String setCookie = "";
        if (respHeaders != null) {
            // 從 Multimap 中提取 Set-Cookie
            List<String> cookies = respHeaders.get("Set-Cookie");
            if (cookies == null) cookies = respHeaders.get("set-cookie");
            if (cookies != null && !cookies.isEmpty()) {
                setCookie = TextUtils.join(";", cookies);
            }
        }

        if (!TextUtils.isEmpty(setCookie)) {
            cookieJar.put(host, setCookie);
            // 🚀 自動補刀邏輯：如果內容太短（可能是驗證頁），帶著 Cookie 再衝一次
            if (res.getBody().trim().length() < 1000) {
                headers.put("Cookie", setCookie);
                res = execute(method, url, body, headers);
            }
        }

        return res;
    }

    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        
        if ("post".equals(method)) {
            // 🛡️ 根據 OkHttp.java 源碼適配：支持直接發送 JSON 字符串
            if (!TextUtils.isEmpty(body) && body.trim().startsWith("{")) {
                return OkHttp.post(url, body, headers);
            } else {
                return OkHttp.post(url, parseToMap(body), headers);
            }
        }
        
        // 默認 GET
        return OkHttp.get(url, parseToMap(body), headers);
    }

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

    private static String getHost(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return urlStr;
        }
    }
}
