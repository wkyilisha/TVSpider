package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {

    private static StringBuilder sb = new StringBuilder("<div style='color:#888;'>--- 凱哥全能矩陣引擎已啟動 ---</div>");
    private static volatile boolean isServerRunning = false;
    private static final int PORT = 10086;

    // 类加载时立即启动服务器，不依赖 log() 触发
    static {
        startServer();
    }

    public static int getPort() { return PORT; }
    public static String getUrl() { return "http://127.0.0.1:" + PORT; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 200000) sb.delete(0, 100000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        sb.append("<div class='line'><span class='time'>[").append(time).append("]</span> ")
          .append("<span class='msg'>").append(msg).append("</span></div>");
    }

    private static synchronized void startServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                log("✅ 10086 服务器启动成功");

                while (true) {
                    try {
                        Socket client = server.accept();
                        // 每个请求开一个线程处理，避免阻塞
                        new Thread(() -> handleClient(client)).start();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                isServerRunning = false;
                log("❌ 服务器启动失败: " + e.getMessage());
            }
        }).start();
    }

    private static void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String reqLine = in.readLine();
            if (reqLine == null) { client.close(); return; }

            String path = reqLine.split(" ")[1];
            Map<String, String> params = parseParams(path);

            OutputStream out = client.getOutputStream();
            String doParam = params.get("do");

            if (path.contains("/?clean") || "clean".equals(doParam)) {
                sb.setLength(0);
                sb.append("<div style='color:red;'>--- 日誌已清空 ---</div>");
                out.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes());
            } else if (path.contains("/get_logs")) {
                byte[] body = sb.toString().getBytes("UTF-8");
                String header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\n\r\n";
                out.write(header.getBytes());
                out.write(body);
            } else if ("livesource".equals(doParam) || "live".equals(doParam) || "iptv".equals(doParam)) {
                handleLiveSource(out);
            } else if ("m3u8".equals(doParam) || "rewrite".equals(doParam)) {
                handleM3U8Proxy(params, out);
            } else if ("stream".equals(doParam)) {
                handleSingleStream(params, out);
            } else {
                out.write(buildLogHtml().getBytes("UTF-8"));
            }

            out.flush();
            client.shutdownOutput();
            client.close();
        } catch (Exception ignored) {}
    }

    private static Map<String, String> parseParams(String path) {
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
        return params;
    }

    private static String buildLogHtml() {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                "<html><head><meta charset='utf-8'><style>" +
                "body{background:#1e1e1e;color:#ddd;font-family:monospace;font-size:13px;margin:0;padding:10px;}" +
                ".header{position:sticky;top:0;background:#1e1e1e;padding:8px;border-bottom:1px solid #444;display:flex;justify-content:space-between;}" +
                ".time{color:#888;}.line{border-bottom:1px solid #333;padding:3px 0;}" +
                "button{background:#0066cc;color:#fff;border:none;padding:6px 12px;border-radius:4px;}</style></head><body>" +
                "<div class='header'><b>📟 凱哥 10086 服务器</b><button onclick='clr()'>🧹 清空</button></div>" +
                "<div id='logs'></div>" +
                "<script>let last='';function clr(){fetch('/?clean').then(()=>location.reload());}" +
                "setInterval(()=>{fetch('/get_logs').then(r=>r.text()).then(d=>{if(d!==last){document.getElementById('logs').innerHTML=d;last=d;window.scrollTo(0,document.body.scrollHeight);}})},800);</script></body></html>";
    }

    // ====================== 直播源 ======================
    private static void handleLiveSource(OutputStream out) throws Exception {
        String url = "https://gh-proxy.org/https://raw.githubusercontent.com/wkyilisha/tvbox/main/iptvpmigu.txt";
        log("📺 抓取直播源...");
        try {
            String content = OkHttp.string(url);
            if (content == null || content.trim().isEmpty()) {
                log("❌ 直播源为空");
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n\r\nEmpty source".getBytes());
                return;
            }
            log("✅ 直播源抓取成功 | 长度: " + content.length());
            byte[] body = content.getBytes("UTF-8");
            String header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\n\r\n";
            out.write(header.getBytes());
            out.write(body);
        } catch (Exception e) {
            log("❌ 直播源失败: " + e.getMessage());
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\nFetch failed".getBytes());
        }
    }

    // ====================== m3u8 域名随机替换 ======================
    private static void handleM3U8Proxy(Map<String, String> params, OutputStream out) throws Exception {
        String m3u8Url = params.get("url");
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\nMissing url".getBytes());
            return;
        }

        log("🔄 m3u8 处理 → " + m3u8Url);

        try {
            String original = OkHttp.string(m3u8Url);
            if (original == null || original.trim().isEmpty()) {
                log("❌ m3u8 内容为空");
                out.write("HTTP/1.1 502 Bad Gateway\r\n\r\nEmpty m3u8".getBytes());
                return;
            }

            String rewritten = rewriteTsDomain(original, m3u8Url);
            byte[] body = rewritten.getBytes("UTF-8");
            String header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl; charset=utf-8\r\nContent-Length: " + body.length + "\r\n\r\n";
            out.write(header.getBytes());
            out.write(body);
            log("✅ m3u8 处理完成");
        } catch (Exception e) {
            log("❌ m3u8 处理失败: " + e.getMessage());
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\nProcess error".getBytes());
        }
    }

    private static String rewriteTsDomain(String content, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        int replaceCount = 0;
        // 整个m3u8用同一个随机域名
        int rand = random.nextInt(16) + 1;
        String newDomain = rand + ".ppnix.com";

        for (String line : content.split("\n")) {
            line = line.trim();

            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }

            // 注释行原样保留
            if (line.startsWith("#")) {
                sb.append(line).append("\n");
                continue;
            }

            // 非注释非空行，统一转成绝对路径
            String fullUrl = makeAbsoluteUrl(line, baseUrl);

            // 替换 ipfs.ppnix.com 域名（无论有没有 .ts 后缀）
            if (fullUrl.contains("ipfs.ppnix.com")) {
                fullUrl = fullUrl.replace("ipfs.ppnix.com", newDomain);
                replaceCount++;
            }

            sb.append(fullUrl).append("\n");
        }

        log("🔀 共替换 " + replaceCount + " 个片段域名 → " + newDomain);
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

    // ====================== 单片段代理（二进制安全） ======================
    private static void handleSingleStream(Map<String, String> params, OutputStream out) throws Exception {
        String url = params.get("url");
        if (url == null || url.isEmpty()) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\nMissing url".getBytes());
            return;
        }

        log("📡 TS 代理 → " + url);
        try {
            byte[] bytes = OkHttp.string(url).getBytes("ISO-8859-1");
            String header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: " + bytes.length + "\r\n\r\n";
            out.write(header.getBytes());
            out.write(bytes);
        } catch (Exception e) {
            log("❌ TS 代理失败: " + e.getMessage());
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\nStream error".getBytes());
        }
    }
}
