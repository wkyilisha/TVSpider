package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {

    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static boolean isServerRunning = false;
    private static final int PORT = 10086;   // 统一使用 10086

    public static int getPort() { return PORT; }
    public static String getUrl() { return "http://127.0.0.1:" + PORT; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
        if (!isServerRunning) startServer();
    }

    private static void startServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                log("✅ 10086 服务器启动成功");

                while (true) {
                    try (Socket client = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String reqLine = in.readLine();
                        if (reqLine == null) continue;

                        String method = reqLine.split(" ")[0];
                        String path = reqLine.split(" ")[1];

                        // 解析 GET 参数
                        Map<String, String> params = new HashMap<>();
                        if (path.contains("?")) {
                            String query = path.split("\\?")[1];
                            for (String kv : query.split("&")) {
                                String[] pair = kv.split("=", 2);
                                if (pair.length == 2) {
                                    try {
                                        params.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }

                        try (OutputStream out = client.getOutputStream()) {
                            String doParam = params.get("do");

                            if (doParam == null && path.contains("/?clean")) {
                                sb.setLength(0);
                                sb.append("<div style='color:red;'>--- 日誌已清空 ---</div>");
                                out.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes());
                            } 
                            else if (path.contains("/get_logs")) {
                                String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + sb.toString();
                                out.write(resp.getBytes("UTF-8"));
                            } 
                            else if ("livesource".equals(doParam) || "live".equals(doParam) || "iptv".equals(doParam)) {
                                handleLiveSource(out);
                            } 
                            else if ("m3u8".equals(doParam) || "rewrite".equals(doParam)) {
                                handleM3U8Proxy(params, out);
                            } 
                            else if ("stream".equals(doParam)) {
                                handleSingleStream(params, out);
                            } 
                            else if ("danmu".equals(doParam) || "pushdanmu".equals(doParam)) {
                                // 你的弹幕处理
                                Object[] result = DanmuHelper.getDanmuResponse(params);
                                // ... （保持你原来的弹幕逻辑）
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\n\r\n".getBytes());
                                if (result.length >= 3 && result[2] instanceof InputStream) {
                                    ((InputStream) result[2]).transferTo(out);
                                }
                            } 
                            else {
                                // 默认返回日志页面
                                out.write(buildLogHtml().getBytes("UTF-8"));
                            }
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                isServerRunning = false;
            }
        }).start();
    }

    private static String buildLogHtml() {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                "<html><head><meta charset='utf-8'><style>" +
                "body{background:#1e1e1e;color:#ddd;font-family:monospace;font-size:13px;margin:0;padding:10px;}" +
                ".header{position:sticky;top:0;background:#1e1e1e;padding:8px;border-bottom:1px solid #444;display:flex;justify-content:space-between;}" +
                ".time{color:#888;}.line{border-bottom:1px solid #333;padding:3px 0;}" +
                "button{background:#0066cc;color:#fff;border:none;padding:6px 12px;border-radius:4px;}" +
                "</style></head><body>" +
                "<div class='header'><b>📟 凱哥 10086 服务器</b><button onclick='clr()'>🧹 清空</button></div>" +
                "<div id='logs'></div>" +
                "<script>" +
                "let last='';function clr(){fetch('/?clean').then(()=>location.reload());}" +
                "setInterval(()=>{fetch('/get_logs').then(r=>r.text()).then(d=>{if(d!==last){document.getElementById('logs').innerHTML=d;last=d;window.scrollTo(0,document.body.scrollHeight);}})},800);" +
                "</script></body></html>";
    }

    // ====================== 直播源 ======================
    private static void handleLiveSource(OutputStream out) throws Exception {
        String url = "https://gh-proxy.org/https://raw.githubusercontent.com/wkyilisha/tvbox/main/iptvpmigu.txt";
        log("📺 抓取直播源...");

        String content = OkHttp.string(url);
        if (content == null || content.trim().isEmpty()) {
            log("❌ 直播源为空");
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\nEmpty source".getBytes());
            return;
        }
        log("✅ 直播源获取成功");
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n\r\n".getBytes());
        out.write(content.getBytes("UTF-8"));
    }

    // ====================== m3u8 TS 重写 ======================
    private static void handleM3U8Proxy(Map<String, String> params, OutputStream out) throws Exception {
        String m3u8Url = params.get("url");
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\nMissing url".getBytes());
            return;
        }

        log("🔄 m3u8 重写 → " + m3u8Url);
        String original = OkHttp.string(m3u8Url);
        String rewritten = rewriteTsToProxy(original, m3u8Url);

        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n\r\n".getBytes());
        out.write(rewritten.getBytes("UTF-8"));
    }

    private static String rewriteTsToProxy(String content, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) { sb.append("\n"); continue; }
            if (line.startsWith("#")) { sb.append(line).append("\n"); continue; }

            if (line.contains(".ts") || line.matches(".*\\.ts\\?.*")) {
                String fullTs = makeAbsoluteUrl(line, baseUrl);
                String encoded = fullTs;
                try { encoded = URLEncoder.encode(fullTs, "UTF-8"); } catch (Exception ignored) {}
                String proxyTs = "http://127.0.0.1:" + PORT + "/?do=stream&url=" + encoded;
                sb.append(proxyTs).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String makeAbsoluteUrl(String path, String base) {
        if (path.startsWith("http")) return path;
        try {
            return new java.net.URL(new java.net.URL(base), path).toString();
        } catch (Exception e) {
            return path;
        }
    }

    // ====================== 单 TS 代理 ======================
    private static void handleSingleStream(Map<String, String> params, OutputStream out) throws Exception {
        String url = params.get("url");
        if (url == null || url.isEmpty()) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\nMissing url".getBytes());
            return;
        }

        log("📡 TS 代理 → " + url);
        String body = OkHttp.string(url);

        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".getBytes());
        out.write(body.getBytes("UTF-8"));
    }
}
