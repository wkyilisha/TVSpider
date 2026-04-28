package com.github.catvod.spider;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import android.text.TextUtils;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KaiGeNet {

    private static final Map<String, String> cookieJar = new ConcurrentHashMap<>();
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36";

    public static OkResult smartRequest(String siteUrl, String method, String url, String body, Map<String, String> headers) {
        String host = getHost(url);
        
        if (headers == null) headers = new HashMap<>();
        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", MOBILE_UA);
        if (!headers.containsKey("Referer")) headers.put("Referer", siteUrl);

        if (cookieJar.containsKey(host)) {
            headers.put("Cookie", cookieJar.get(host));
        }

        // 第一次請求
        OkResult res = execute(method, url, body, headers);

        // 🛡️ 兼容處理 1: 這裡不用 getHeader()，直接獲取全部 Map 再拿 Cookie
        // 這是為了兼容所有版本的 OkResult
        Map<String, String> respHeaders = res.getHeaders(); 
        String setCookie = "";
        if (respHeaders != null) {
            for (Map.Entry<String, String> entry : respHeaders.entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                    setCookie = entry.getValue();
                    break;
                }
            }
        }

        if (!TextUtils.isEmpty(setCookie)) {
            cookieJar.put(host, setCookie);
            // 自動補刀邏輯：如果頁面內容太短（可能是驗證頁），帶著 Cookie 再刷一次
            if (res.getBody().trim().length() < 800) {
                headers.put("Cookie", setCookie);
                res = execute(method, url, body, headers);
            }
        }

        return res;
    }

    private static OkResult execute(String method, String url, String body, Map<String, String> headers) {
        method = (method == null) ? "get" : method.toLowerCase();
        
        if ("post".equals(method)) {
            // 🛡️ 兼容處理 2: 統一將 Body 轉成 Map 發送
            // 因為老版本 OkHttp.post 通常只支持 (String url, Map params, Map headers)
            return OkHttp.post(url, parseToMap(body), headers);
        }
        
        // 默認 GET
        return OkHttp.get(url, null, headers);
    }

    private static Map<String, String> parseToMap(String body) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(body)) return map;
        try {
            // 如果 body 是 JSON 格式，老版本不支持直接發送，這裡先簡單切分
            // 建議凱哥在 JSON 規則裡，search_body 寫成 a=1&b=2 這種格式最穩
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
