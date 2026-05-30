package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class Proxy extends Spider {

    private static int port = -1;
    private static final StringBuilder logBuilder = new StringBuilder();
    private static final int MAX_LOG_SIZE = 300000;
    private static final String LOCAL_HOST = "http://127.0.0.1:";

    public static int getPort() {
        if (port <= 0) detectPort();
        return port;
    }

    public static String getUrl() {
        return LOCAL_HOST + getPort() + "/proxy";
    }

    private static void detectPort() {
        for (int i = 9978; i < 10000; i++) {
            try {
                String testUrl = LOCAL_HOST + i + "/proxy?do=ck";
                if (httpGetSimple(testUrl).equals("ok")) {
                    port = i;
                    SpiderDebug.log("✅ Proxy 端口已确定: " + i);
                    return;
                }
            } catch (Exception ignored) {}
        }
        port = 9978;
        SpiderDebug.log("⚠️ 使用默认端口 9978");
    }

    public static void log(String msg) {
        if (msg == null) return;
        synchronized (logBuilder) {
            if (logBuilder.length() > MAX_LOG_SIZE) logBuilder.delete(0, MAX_LOG_SIZE / 2);
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            logBuilder.append("<div class='line'><span class='time'>[").append(time)
                      .append("]</span> <span class='msg'>").append(escapeHtml(msg))
                      .append("</span></div>\n");
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String getLogsHtml() {
        synchronized (logBuilder) { return logBuilder.toString(); }
    }

    private static String buildLogPage() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><style>" +
                "body{background:#1e1e1e;color:#ddd;font-family:monospace;font-size:13px;margin:0;padding:10px;}" +
                ".header{position:sticky;top:0;background:#1e1e1e;padding:10px;border-bottom:1px solid #444;display:flex;justify-content:space-between;z-index:10;}" +
                ".time{color:#888;}.line{border-bottom:1px solid #333;padding:3px 0;}" +
                "button{background:#0066cc;color:#fff;border:none;padding:6px 14px;border-radius:4px;}</style></head><body>" +
                "<div class='header'><b>📟 凱哥矩陣日志监控</b>" +
                "<button onclick=\"fetch('" + LOCAL_HOST + getPort() + "/proxy?do=cleanlog').then(()=>location.reload())\">🧹 清空</button></div>" +
                "<div id='logs'>" + getLogsHtml() + "</div>" +
                "<script>let last='';setInterval(()=>{fetch('" + LOCAL_HOST + getPort() + "/proxy?do=getlog').then(r=>r.text()).then(d=>{if(d!==last){document.getElementById('logs').innerHTML=d;last=d;window.scrollTo(0,document.body.scrollHeight);}});},800);</script></body></html>";
    }

    public Object[] proxy(Map<String, String> params) {
        if (params == null) return null;
        String doParam = params.get("do");

        log("📨 Proxy 请求 | do=" + doParam);

        try {
            if ("ck".equals(doParam)) {
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes())};
            }
            if ("getlog".equals(doParam)) {
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream(getLogsHtml().getBytes("UTF-8"))};
            }
            if ("cleanlog".equals(doParam)) {
                synchronized (logBuilder) { logBuilder.setLength(0); }
                log("🧹 日志已清空");
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes())};
            }
            if ("log".equals(doParam) || doParam == null) {
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(buildLogPage().getBytes("UTF-8"))};
            }

            if ("livesource".equals(doParam) || "live".equals(doParam) || "iptv".equals(doParam)) {
                return handleLiveSource();
            }
            if ("m3u8".equals(doParam) || "rewrite".equals(doParam)) {
                return handleM3U8Proxy(params);
            }
            if ("stream".equals(doParam)) {
                return handleSingleStream(params);
            }
            if ("danmu".equals(doParam) || "pushdanmu".equals(doParam)) {
                return handleDanmu(params);
            }

            return null;
        } catch (Exception e) {
            log("❌ Proxy 异常: " + e.getMessage());
            return new Object[]{500, "text/plain", new ByteArrayInputStream("Proxy Error".getBytes())};
        }
    }

    // ====================== 完整直播源 ======================
    private Object[] handleLiveSource() {
        String sourceUrl = "https://gh-proxy.org/https://raw.githubusercontent.com/wkyilisha/tvbox/main/iptvpmigu.txt";
        log("📺 正在抓取直播源...");

        try {
            String content = httpGet(sourceUrl);
            if (content == null || content.trim().isEmpty()) {
                log("❌ 直播源为空");
                return new Object[]{502, "text/plain", new ByteArrayInputStream("Empty live source".getBytes())};
            }
            String m3u = convertToM3U(content);
            log("✅ 直播源获取成功");
            return new Object[]{200, "application/vnd.apple.mpegurl; charset=utf-8", new ByteArrayInputStream(m3u.getBytes("UTF-8"))};
        } catch (Exception e) {
            log("❌ 直播源失败: " + e.getMessage());
            return new Object[]{502, "text/plain", new ByteArrayInputStream("Fetch failed".getBytes())};
        }
    }

    private String convertToM3U(String original) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        String group = "默认分组";
        for (String line : original.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.contains("#genre#")) {
                group = line.split(",")[0].trim();
            } else if (line.contains(",")) {
                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    sb.append("#EXTINF:-1,group-title=\"").append(group).append("\",")
                      .append(parts[0].trim()).append("\n").append(parts[1].trim()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ====================== m3u8 TS 重写 ======================
    private Object[] handleM3U8Proxy(Map<String, String> params) {
        String m3u8Url = params.get("url");
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            return new Object[]{400, "text/plain", new ByteArrayInputStream("Missing url".getBytes())};
        }

        log("🔄 m3u8 TS 重写 → " + m3u8Url);

        try {
            String original = httpGet(m3u8Url);
            String rewritten = rewriteTsToProxy(original, m3u8Url);
            return new Object[]{200, "application/vnd.apple.mpegurl; charset=utf-8", new ByteArrayInputStream(rewritten.getBytes("UTF-8"))};
        } catch (Exception e) {
            log("❌ m3u8 重写失败: " + e.getMessage());
            return new Object[]{500, "text/plain", new ByteArrayInputStream("M3U8 error".getBytes())};
        }
    }

    private String rewriteTsToProxy(String content, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) { sb.append("\n"); continue; }
            if (line.startsWith("#")) { sb.append(line).append("\n"); continue; }

            if (line.contains(".ts") || line.matches(".*\\.ts\\?.*")) {
                String fullTs = makeAbsoluteUrl(line, baseUrl);
                String encoded = fullTs;
                try {
                    encoded = URLEncoder.encode(fullTs, "UTF-8");
                } catch (Exception ignored) {}
                String proxyTs = getUrl() + "?do=stream&url=" + encoded;
                sb.append(proxyTs).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String makeAbsoluteUrl(String path, String base) {
        if (path.startsWith("http")) return path;
        try {
            return new URL(new URL(base), path).toString();
        } catch (Exception e) {
            return path;
        }
    }

    // ====================== TS 单流代理 ======================
    private Object[] handleSingleStream(Map<String, String> params) {
        String url = params.get("url");
        if (url == null || url.isEmpty()) {
            return new Object[]{400, "text/plain", new ByteArrayInputStream("Missing url".getBytes())};
        }

        log("📡 TS 代理 → " + url);

        try {
            byte[] body = httpGetBytes(url);
            if (body == null || body.length == 0) {
                log("⚠️ TS 返回空内容");
                return new Object[]{204, "text/plain", new ByteArrayInputStream("No content".getBytes())};
            }
            log("✅ TS 代理成功 | 大小: " + body.length + " bytes");
            return new Object[]{200, "video/mp2t", new ByteArrayInputStream(body)};
        } catch (Exception e) {
            log("❌ TS 代理失败: " + e.getMessage());
            return new Object[]{502, "text/plain", new ByteArrayInputStream("Stream error".getBytes())};
        }
    }

    private Object[] handleDanmu(Map<String, String> params) {
        String title = params.get("title");
        String episode = params.get("episode");
        if (title == null || episode == null) {
            return new Object[]{400, "text/plain", new ByteArrayInputStream("Missing title or episode".getBytes())};
        }
        log("🎯 弹幕请求 → " + title + " | " + episode);
        return DanmuHelper.getDanmuResponse(params);
    }

    // ====================== 基础网络请求 ======================
    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }

    private byte[] httpGetBytes(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    private static String httpGetSimple(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString().trim();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
