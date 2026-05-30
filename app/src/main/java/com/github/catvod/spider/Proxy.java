package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.en.NetPan;
import com.github.catvod.parser.MixDemo;
import com.github.catvod.parser.MixWeb;
import com.github.catvod.spider.merge.C0295;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {

    private static int port = -1;
    private static final StringBuilder logBuilder = new StringBuilder();
    private static final int MAX_LOG_SIZE = 300000;
    private static final String LOCAL_HOST = "http://127.0.0.1:";

    // ====================== 端口探测 ======================
    public static String getUrl() {
        if (port <= 0) detectPort();
        return LOCAL_HOST + port + "/proxy";
    }

    private static void detectPort() {
        for (int i = 9978; i < 10000; i++) {
            try {
                if (C0295.m1089(LOCAL_HOST + i + "/proxy?do=ck", null).equals("ok")) {
                    port = i;
                    SpiderDebug.log("✅ Proxy 服务端口已确定: " + i);
                    return;
                }
            } catch (Exception ignored) {}
        }
        port = 9978;
    }

    // ====================== 日志系统 ======================
    public static void log(String msg) {
        if (msg == null) return;
        synchronized (logBuilder) {
            if (logBuilder.length() > MAX_LOG_SIZE) {
                logBuilder.delete(0, MAX_LOG_SIZE / 2);
            }
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
        synchronized (logBuilder) {
            return logBuilder.toString();
        }
    }

    private static String buildLogPage() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<style>body{background:#1e1e1e;color:#ddd;font-family:monospace;font-size:13px;margin:0;padding:10px;}" +
                ".header{position:sticky;top:0;background:#1e1e1e;padding:10px;border-bottom:1px solid #444;display:flex;justify-content:space-between;z-index:10;}" +
                ".time{color:#888;}.line{border-bottom:1px solid #333;padding:3px 0;}" +
                "button{background:#0066cc;color:#fff;border:none;padding:6px 14px;border-radius:4px;}</style></head><body>" +
                "<div class='header'><b>📟 凱哥矩陣日志监控</b>" +
                "<button onclick=\"fetch('" + LOCAL_HOST + port + "/proxy?do=cleanlog').then(()=>location.reload())\">🧹 清空</button></div>" +
                "<div id='logs'>" + getLogsHtml() + "</div>" +
                "<script>" +
                "let last='';setInterval(()=>{fetch('" + LOCAL_HOST + port + "/proxy?do=getlog').then(r=>r.text()).then(d=>{if(d!==last){document.getElementById('logs').innerHTML=d;last=d;window.scrollTo(0,document.body.scrollHeight);}});},800);" +
                "</script></body></html>";
    }

    // ====================== 主入口 ======================
    public Object[] proxy(Map<String, String> params) {
        if (params == null) return null;
        String doParam = params.get("do");

        log("📨 Proxy 请求 | do=" + doParam + " | params=" + params);

        try {
            if ("ck".equals(doParam)) {
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes())};

            } else if ("getlog".equals(doParam)) {
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream(getLogsHtml().getBytes("UTF-8"))};

            } else if ("cleanlog".equals(doParam)) {
                synchronized (logBuilder) { logBuilder.setLength(0); }
                log("🧹 日志已清空");
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes())};

            } else if ("log".equals(doParam) || doParam == null) {
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(buildLogPage().getBytes("UTF-8"))};

            // ================== 直播源（完整列表） ==================
            } else if ("livesource".equals(doParam) || "live".equals(doParam) || "iptv".equals(doParam)) {
                return handleLiveSource();

            // ================== m3u8 TS 重写代理 ==================
            } else if ("m3u8".equals(doParam) || "rewrite".equals(doParam)) {
                return handleM3U8Proxy(params);

            // ================== 单 TS 流代理 ==================
            } else if ("stream".equals(doParam)) {
                return handleSingleStream(params);

            // ================== 弹幕 ==================
            } else if ("danmu".equals(doParam) || "pushdanmu".equals(doParam)) {
                return handleDanmu(params);

            // ================== 其他官方功能 ==================
            } else if ("ali".equals(doParam)) {
                return NetPan.proxy(params);
            } else if ("web".equals(doParam)) {
                return MixWeb.loadHtml(params.get("url"), params.get("type"));
            } else if ("demo".equals(doParam)) {
                return MixDemo.loadHtml(params.get("url"), params.get("type"));
            }

            return null;
        } catch (Exception e) {
            log("❌ Proxy 异常: " + e.getMessage());
            return new Object[]{500, "text/plain", new ByteArrayInputStream("Proxy Error".getBytes())};
        }
    }

    // ====================== 完整直播源（动态抓取） ======================
    private Object[] handleLiveSource() {
        String sourceUrl = "https://gh-proxy.org/https://raw.githubusercontent.com/wkyilisha/tvbox/main/iptvpmigu.txt";
        log("📺 正在抓取最新咪咕直播源...");

        try {
            byte[] data = com.github.catvod.spider.merge.f0.d.b(sourceUrl, null);
            String content = new String(data, "UTF-8");
            String m3u = convertToM3U(content);

            return new Object[]{
                200,
                "application/vnd.apple.mpegurl; charset=utf-8",
                new ByteArrayInputStream(m3u.getBytes("UTF-8"))
            };
        } catch (Exception e) {
            log("❌ 直播源抓取失败: " + e.getMessage());
            return new Object[]{500, "text/plain", new ByteArrayInputStream("Live source error".getBytes())};
        }
    }

    private String convertToM3U(String original) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        String[] lines = original.split("\n");
        String group = "默认分组";

        for (String line : lines) {
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

    // ====================== m3u8 TS 重写代理 ======================
    private Object[] handleM3U8Proxy(Map<String, String> params) {
        String m3u8Url = params.get("url");
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            return new Object[]{400, "text/plain", new ByteArrayInputStream("Missing url".getBytes())};
        }

        log("🔄 m3u8 TS 重写 → " + m3u8Url);

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", params.getOrDefault("ua", "Mozilla/5.0"));

            byte[] data = com.github.catvod.spider.merge.f0.d.b(m3u8Url, headers);
            String original = new String(data, "UTF-8");
            String rewritten = rewriteTsToProxy(original, m3u8Url);

            return new Object[]{
                200,
                "application/vnd.apple.mpegurl; charset=utf-8",
                new ByteArrayInputStream(rewritten.getBytes("UTF-8"))
            };
        } catch (Exception e) {
            log("❌ m3u8 重写失败: " + e.getMessage());
            return new Object[]{500, "text/plain", new ByteArrayInputStream("M3U8 proxy error".getBytes())};
        }
    }

    private String rewriteTsToProxy(String content, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (line.startsWith("#")) {
                sb.append(line).append("\n");
                continue;
            }
            if (line.contains(".ts") || line.matches(".*\\.ts\\?.*")) {
                String fullTs = makeAbsoluteUrl(line, baseUrl);
                String proxyTs = Proxy.getUrl() + "?do=stream&url=" + java.net.URLEncoder.encode(fullTs, "UTF-8");
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
            return new java.net.URL(new java.net.URL(base), path).toString();
        } catch (Exception e) {
            return path;
        }
    }

    // ====================== 单 TS 流代理 ======================
    private Object[] handleSingleStream(Map<String, String> params) {
        String url = params.get("url");
        if (url == null) return new Object[]{400, "text/plain", new ByteArrayInputStream("Missing url".getBytes())};

        log("📡 TS 流代理 → " + url);

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0");

            byte[] body = com.github.catvod.spider.merge.f0.d.b(url, headers);
            return new Object[]{
                200,
                "video/mp2t",
                new ByteArrayInputStream(body)
            };
        } catch (Exception e) {
            log("❌ TS 代理失败: " + e.getMessage());
            return new Object[]{502, "text/plain", new ByteArrayInputStream("Stream error".getBytes())};
        }
    }

    // ====================== 弹幕处理 ======================
    private Object[] handleDanmu(Map<String, String> params) {
        String title = params.get("title");
        String episode = params.get("episode");
        if (title == null || episode == null) {
            return new Object[]{400, "text/plain", new ByteArrayInputStream("Missing title or episode".getBytes())};
        }
        log("🎯 弹幕请求 → " + title + " | " + episode);
        return DanmuHelper.getDanmuResponse(params);
    }
}
