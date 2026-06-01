package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64;
import org.json.JSONObject;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凱哥工具鏈引擎 1.0
 *
 * 調用入口：KaiGeTool.process(input, toolChain, host, varCache, lastHtml)
 * toolChain 格式：步骤1#步骤2#步骤3
 * 每個步驟的輸出是下個步驟的輸入
 *
 * 支持工具：
 *   編碼：b64/解b64  url/解url  unicode  hex/HEX/解hex
 *   哈希：md5/MD5  sha1/sha256/sha512
 *   加密：解aes/aes  解des/des  rc4  解aes通用格式
 *   字符：>>替換  顛倒  截取  分割截取  計算  ascii/解ascii  字符映射  異或  隨機字符
 *   網絡：源碼  重定向
 *   控制：如果...那么...否则...  循环-分割符-工具命令  返回
 *   其他：json格式化  時間  日誌  讀取  寫入  設置變量
 */
public class KaiGeTool {

    // ==================== 主入口 ====================

    /**
     * @param input     工具鏈的初始輸入
     * @param toolChain 工具命令串，以 # 分隔
     * @param host      站點域名，用於源碼請求
     * @param varCache  全局變量緩存（可讀寫）
     * @param lastHtml  最近一次請求的 HTML 源碼
     * @param ruleJson  完整規則 JSON（用於讀取/寫入工具）
     */
    public static String process(String input, String toolChain,
                                  String host,
                                  Map<String, String> varCache,
                                  String lastHtml,
                                  JSONObject ruleJson) {
        if (TextUtils.isEmpty(toolChain)) return input;

        // 拆分工具步驟，# 分隔，但跳過括號內的 #（如 如果(a#b)那么(c)）
        String[] steps = splitToolSteps(toolChain);
        String current = input == null ? "" : input;

        for (String rawStep : steps) {
            String step = rawStep.trim();
            if (step.isEmpty()) continue;
            try {
                current = runStep(current, step, host, varCache, lastHtml, ruleJson);
            } catch (Exception e) {
                // 單步異常不中斷整個工具鏈，保持當前值繼續
            }
        }
        return current;
    }

    // ==================== 單步執行 ====================

    private static String runStep(String input, String step,
                                   String host,
                                   Map<String, String> varCache,
                                   String lastHtml,
                                   JSONObject ruleJson) throws Exception {

        // ---------- 編碼 ----------
        if (step.equals("b64") || step.equals("b64-1")) {
            return Base64.encodeToString(input.getBytes("UTF-8"), Base64.NO_WRAP);
        }
        if (step.equals("b64-2")) {
            return Base64.encodeToString(input.getBytes("UTF-8"), Base64.URL_SAFE | Base64.NO_WRAP);
        }
        if (step.equals("b64-3")) {
            return Base64.encodeToString(input.getBytes("UTF-8"), Base64.NO_PADDING | Base64.NO_WRAP);
        }
        if (step.equals("解b64") || step.equals("解b64-1")) {
            return new String(Base64.decode(input, Base64.DEFAULT), "UTF-8");
        }
        if (step.equals("解b64-2")) {
            return new String(Base64.decode(input, Base64.URL_SAFE), "UTF-8");
        }
        if (step.startsWith("url")) {
            String charset = step.contains("-") ? step.substring(step.indexOf("-") + 1) : "UTF-8";
            return URLEncoder.encode(input, charset);
        }
        if (step.startsWith("解url")) {
            String charset = step.contains("-") ? step.substring(step.indexOf("-") + 1) : "UTF-8";
            return URLDecoder.decode(input, charset);
        }
        if (step.equals("unicode")) {
            return unicodeDecode(input);
        }
        if (step.equals("hex")) {
            // b64 → 小寫 hex
            byte[] bytes = Base64.decode(input, Base64.DEFAULT);
            return bytesToHex(bytes, false);
        }
        if (step.equals("HEX")) {
            byte[] bytes = Base64.decode(input, Base64.DEFAULT);
            return bytesToHex(bytes, true);
        }
        if (step.equals("解hex")) {
            byte[] bytes = hexToBytes(input);
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        }

        // ---------- 哈希 ----------
        if (step.equals("md5")) return md5(input, false);
        if (step.equals("MD5")) return md5(input, true);
        if (step.equals("sha1") || step.equals("SHA-1")) return sha(input, "SHA-1");
        if (step.equals("sha256") || step.equals("SHA-256") || step.equals("SHA")) return sha(input, "SHA-256");
        if (step.equals("sha512") || step.equals("SHA-512")) return sha(input, "SHA-512");
        if (step.equals("sha224") || step.equals("SHA-224")) return sha(input, "SHA-224");
        if (step.equals("sha384") || step.equals("SHA-384")) return sha(input, "SHA-384");

        // ---------- 字符集轉換 ----------
        if (step.matches("[A-Z0-9\\-]+>[A-Z0-9\\-]+")) {
            String[] parts = step.split(">");
            return new String(input.getBytes(parts[0].trim()), parts[1].trim());
        }

        // ---------- AES 通用格式 ----------
        // 格式：解aes-key-iv-AES/CBC/PKCS5Padding
        // 格式：aes-key-iv-AES/CBC/PKCS5Padding （加密）
        // 格式：解aes-key-iv  或  解aes-key-空  （默認CBC）
        if (step.startsWith("解aes") || step.startsWith("解密aes") || step.startsWith("解AES")) {
            return aesDecrypt(input, step, false);
        }
        if (step.startsWith("aes") || step.startsWith("AES")) {
            return aesEncrypt(input, step);
        }

        // ---------- DES/3DES ----------
        if (step.startsWith("解des") || step.startsWith("解DES")) {
            return desDecrypt(input, step);
        }
        if (step.startsWith("des") || step.startsWith("DES")) {
            return desEncrypt(input, step);
        }

        // ---------- RC4 ----------
        if (step.startsWith("解rc4") || step.startsWith("rc4")) {
            String key = step.substring(step.indexOf("-") + 1);
            return rc4(input, key);
        }

        // ---------- 字符串操作 ----------
        if (step.equals("顛倒") || step.equals("颠倒")) {
            return new StringBuilder(input).reverse().toString();
        }

        // >> 替換：a>>b 或多個 a>>b♯c>>d
        if (step.contains(">>")) {
            return handleReplace(input, step);
        }

        // 截取：c&&d  或  c+d截取e（使用内聯實現，不依賴 KaiGeEngine）
        if (step.contains("截取")) {
            String cutRule = step.replace("截取", "&&");
            return inlineExtract(input, cutRule);
        }

        // 分割截取：分割符-截取規則-合並符
        if (step.startsWith("分割截取-")) {
            return splitExtract(input, step.substring(5));
        }

        // 計算：數學表達式
        if (step.equals("計算") || step.equals("计算")) {
            return calculate(input, false, false);
        }
        if (step.equals("計算\\+") || step.equals("计算\\+")) {
            return calculate(input, true, false);
        }
        if (step.equals("計算.") || step.equals("计算.")) {
            return calculate(input, false, true);
        }

        // ASCII 編解碼
        if (step.startsWith("asiic") || step.startsWith("解asiic")) {
            return handleAscii(input, step);
        }

        // 異或
        if (step.startsWith("異或-") || step.startsWith("异或-")) {
            String key = step.substring(step.indexOf("-") + 1);
            return xorString(input, key);
        }

        // 字符映射
        if (step.startsWith("字符映射-")) {
            String params = step.substring(5);
            String[] parts = params.split("-", 2);
            if (parts.length == 2) return charMap(input, parts[0], parts[1]);
        }

        // 隨機字符
        if (step.startsWith("隨機字符-") || step.startsWith("随机字符-")) {
            return randomChar(input, step);
        }

        // JSON 格式化
        if (step.equals("json格式化")) {
            try {
                JSONObject obj = new JSONObject(input);
                return obj.toString(2);
            } catch (Exception e) { return input; }
        }

        // 時間格式化
        if (step.equals("時間") || step.equals("时间")) {
            return formatDuration(input, "");
        }
        if (step.startsWith("時間") || step.startsWith("时间")) {
            String sep = step.substring(2);
            return formatDuration(input, sep);
        }

        // ---------- 網絡 ----------
        if (step.equals("源碼") || step.equals("源码")) {
            if (input.startsWith("http")) {
                OkResult res = KaiGeNet.smartRequest(host, "get", input, null, null);
                return res.getBody() != null ? res.getBody() : "";
            }
            return lastHtml != null ? lastHtml : input;
        }

        if (step.equals("源碼轉b64") || step.equals("源码转b64")) {
            if (input.startsWith("http")) {
                OkResult res = KaiGeNet.smartRequest(host, "get", input, null, null);
                byte[] body = res.getBodyBytes();
                if (body != null) return Base64.encodeToString(body, Base64.NO_WRAP);
            }
            return input;
        }

        if (step.equals("重定向")) {
            try {
                Map<String, java.util.List<String>> headers = OkHttp.getLocationHeader(input, null);
                String loc = OkHttp.getLocation(headers);
                return TextUtils.isEmpty(loc) ? input : loc;
            } catch (Exception e) { return input; }
        }

        // ---------- 控制流 ----------

        // 如果(條件)那么(工具)，否則如果(條件2)那么(工具2)，否則(默認工具)
        if (step.startsWith("如果") || step.startsWith("如果(")) {
            return handleIfElse(input, step, host, varCache, lastHtml, ruleJson);
        }

        // 循環-分割符-工具命令
        if (step.startsWith("循環-") || step.startsWith("循环-")) {
            return handleLoop(input, step, host, varCache, lastHtml, ruleJson);
        }

        // 返回 固定值 或 返回元素
        if (step.startsWith("返回")) {
            String val = step.substring(2).trim();
            if (val.isEmpty() || val.equals("元素")) return input;
            // 返回 變量
            if (val.startsWith("變量") || val.startsWith("变量")) {
                String varName = val.substring(2).trim();
                return varCache.containsKey(varName) ? varCache.get(varName) : "";
            }
            return val;
        }

        // ---------- 配置讀寫 ----------

        // 讀取-字段名
        if (step.startsWith("讀取-") || step.startsWith("读取-")) {
            String field = step.substring(step.indexOf("-") + 1);
            return ruleJson != null ? ruleJson.optString(field, "") : "";
        }

        // 寫入-字段名 或 寫入-字段名:值
        if (step.startsWith("寫入") || step.startsWith("写入")) {
            String params = step.substring(2).trim();
            if (params.startsWith("-")) params = params.substring(1);
            if (params.contains(":")) {
                String[] kv = params.split(":", 2);
                if (ruleJson != null) try { ruleJson.put(kv[0].trim(), kv[1].trim()); } catch (Exception ignored) {}
            } else {
                if (ruleJson != null) try { ruleJson.put(params.trim(), input); } catch (Exception ignored) {}
            }
            return input;
        }

        // 設置變量-名 或 設置變量-名:值
        if (step.startsWith("設置變量") || step.startsWith("设置变量")) {
            String params = step.substring(4).trim();
            if (params.startsWith("-")) params = params.substring(1);
            if (params.contains(":")) {
                String[] kv = params.split(":", 2);
                varCache.put(kv[0].trim(), kv[1].trim());
            } else {
                varCache.put(params.trim(), input);
            }
            return input;
        }

        // 日誌（直接打印，不影響值）
        if (step.equals("日誌") || step.equals("日志") || step.startsWith("日誌-") || step.startsWith("日志-")) {
            String msg = step.contains("-") ? step.substring(step.indexOf("-") + 1) : input;
            if (msg.equals("全部變量") || msg.equals("全部变量")) {
                StringBuilder sb = new StringBuilder("[全部變量]\n");
                for (Map.Entry<String, String> e : varCache.entrySet()) sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
                try { Proxy.log(sb.toString()); } catch (Exception ignored) {}
            } else {
                try { Proxy.log("[工具日誌] " + msg); } catch (Exception ignored) {}
            }
            return input;
        }

        // ---------- 命令開關 ----------
        if (step.startsWith("命令開") || step.startsWith("命令开")) return input;
        if (step.startsWith("命令關") || step.startsWith("命令关")) return input;

        return input;
    }

    // ==================== 條件語句 ====================

    /**
     * 格式：如果(條件)那么(工具)，否則如果(條件2)那么(工具2)，否則(默認工具)
     * 括號可省略，中文逗號必須保留
     */
    private static String handleIfElse(String input, String expr,
                                        String host, Map<String, String> varCache,
                                        String lastHtml, JSONObject ruleJson) {
        // 按 ，否則 分割各分支
        String[] branches = expr.split("，否[则則]");
        for (String branch : branches) {
            branch = branch.trim();
            if (branch.startsWith("如果")) {
                // 解析條件和那么部分
                int thenIdx = branch.indexOf("那[么麼]".replaceAll("\\[.+?\\]", ""));
                // 中文"那么"或"那麼"
                int thenPos = findChinese(branch, "那么");
                if (thenPos < 0) thenPos = findChinese(branch, "那麼");
                if (thenPos < 0) continue;

                String condPart = branch.substring(branch.startsWith("如果(") ? 3 : 2, thenPos).trim();
                condPart = stripParens(condPart);
                String toolPart = branch.substring(thenPos + 2).trim();
                toolPart = stripParens(toolPart);

                if (evalCondition(input, condPart, varCache)) {
                    if (TextUtils.isEmpty(toolPart)) return "";
                    return process(input, toolPart, host, varCache, lastHtml, ruleJson);
                }
            } else {
                // 默認否則分支
                String toolPart = stripParens(branch.replace("否則", "").replace("否则", "").trim());
                if (!TextUtils.isEmpty(toolPart)) {
                    return process(input, toolPart, host, varCache, lastHtml, ruleJson);
                }
            }
        }
        return input;
    }

    private static boolean evalCondition(String input, String cond, Map<String, String> varCache) {
        // 替換變量
        for (Map.Entry<String, String> e : varCache.entrySet()) {
            cond = cond.replace("{{" + e.getKey() + "}}", e.getValue());
        }

        String[] ops = {"不等于", "不等於", "等于", "等於", "大于", "大於", "小于", "小於",
                        "不含", "包含", "不開始于", "不开始于", "開始于", "开始于",
                        "不結束于", "不结束于", "結束于", "结束于"};
        for (String op : ops) {
            int idx = cond.indexOf(op);
            if (idx < 0) continue;
            String left  = cond.substring(0, idx).trim();
            String right = cond.substring(idx + op.length()).trim();
            String lVal  = left.isEmpty() ? input : (varCache.containsKey(left) ? varCache.get(left) : left);
            String rVal  = right.equals("空") ? "" : (varCache.containsKey(right) ? varCache.get(right) : right);

            // 字數比較
            boolean byLen = lVal.matches("\\d+") && rVal.matches("\\d+") && cond.contains("字數");
            int lNum = byLen ? lVal.length() : tryInt(lVal);
            int rNum = byLen ? rVal.length() : tryInt(rVal);

            switch (op) {
                case "等于": case "等於": return lVal.equals(rVal);
                case "不等于": case "不等於": return !lVal.equals(rVal);
                case "大于": case "大於": return lNum > rNum;
                case "小于": case "小於": return lNum < rNum;
                case "包含": return lVal.contains(rVal);
                case "不含": return !lVal.contains(rVal);
                case "開始于": case "开始于": return lVal.startsWith(rVal);
                case "不開始于": case "不开始于": return !lVal.startsWith(rVal);
                case "結束于": case "结束于": return lVal.endsWith(rVal);
                case "不結束于": case "不结束于": return !lVal.endsWith(rVal);
            }
        }
        return !input.isEmpty();
    }

    // ==================== 循環語句 ====================

    /**
     * 格式：循環-分割符-工具命令
     * 把 input 按分割符切開，對每個元素執行工具命令，得到非空結果立即返回
     */
    private static String handleLoop(String input, String step,
                                      String host, Map<String, String> varCache,
                                      String lastHtml, JSONObject ruleJson) {
        // 去掉 "循環-" 前綴
        String rest = step.startsWith("循環-") ? step.substring(3) : step.substring(3);
        int secondDash = rest.indexOf("-");
        if (secondDash < 0) return input;

        String sep     = rest.substring(0, secondDash);
        String toolCmd = rest.substring(secondDash + 1);

        // 處理轉義分割符
        sep = sep.replace("\\n", "\n").replace("\\t", "\t");

        String[] elements = input.split(Pattern.quote(sep), -1);
        for (int i = 0; i < elements.length; i++) {
            String elem = elements[i].trim();
            if (elem.isEmpty()) continue;
            // 替換 <序號> 為當前循環次數
            String cmd = toolCmd.replace("<序號>", String.valueOf(i + 1));
            String result = process(elem, cmd, host, varCache, lastHtml, ruleJson);
            if (!TextUtils.isEmpty(result)) return result;
        }
        return "";
    }

    // ==================== AES ====================

    private static String aesDecrypt(String input, String step, boolean outputB64) throws Exception {
        // 解析參數：解aes-key-iv-AES/CBC/PKCS5Padding
        boolean isHexInput = step.toUpperCase().contains("AES-") && step.substring(0, 4).toUpperCase().equals("解AE") == false;
        String paramStr = step.replaceFirst("(?i)解密?aes", "").replaceFirst("^-", "");
        String[] p = paramStr.split("-", 3);
        String keyStr  = p.length > 0 ? p[0].trim() : "";
        String ivStr   = p.length > 1 ? p[1].trim() : "";
        String modeStr = p.length > 2 ? p[2].trim() : "AES/CBC/PKCS5Padding";

        if (modeStr.isEmpty() || modeStr.equals("空")) modeStr = (ivStr.isEmpty() || ivStr.equals("空")) ? "AES/ECB/PKCS5Padding" : "AES/CBC/PKCS5Padding";

        byte[] keyBytes = resolveKeyBytes(keyStr);
        byte[] ivBytes  = (ivStr.isEmpty() || ivStr.equals("空")) ? null : resolveKeyBytes(ivStr);
        byte[] cipherBytes = isHexInput ? hexToBytes(input) : Base64.decode(input, Base64.DEFAULT);

        Cipher cipher = Cipher.getInstance(modeStr);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        if (ivBytes != null) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
        }
        byte[] result = cipher.doFinal(cipherBytes);
        return outputB64 ? Base64.encodeToString(result, Base64.NO_WRAP) : new String(result, "UTF-8");
    }

    private static String aesEncrypt(String input, String step) throws Exception {
        String paramStr = step.replaceFirst("(?i)aes", "").replaceFirst("^-", "");
        String[] p = paramStr.split("-", 3);
        String keyStr  = p.length > 0 ? p[0].trim() : "";
        String ivStr   = p.length > 1 ? p[1].trim() : "";
        String modeStr = p.length > 2 ? p[2].trim() : "AES/CBC/PKCS5Padding";
        boolean hexOut = modeStr.toLowerCase().contains("hex");
        if (modeStr.isEmpty() || modeStr.equals("空")) modeStr = (ivStr.isEmpty() || ivStr.equals("空")) ? "AES/ECB/PKCS5Padding" : "AES/CBC/PKCS5Padding";

        byte[] keyBytes = resolveKeyBytes(keyStr);
        byte[] ivBytes  = (ivStr.isEmpty() || ivStr.equals("空")) ? null : resolveKeyBytes(ivStr);

        Cipher cipher = Cipher.getInstance(modeStr.replace("hex", "").trim());
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        if (ivBytes != null) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        }
        byte[] result = cipher.doFinal(input.getBytes("UTF-8"));
        return hexOut ? bytesToHex(result, false) : Base64.encodeToString(result, Base64.NO_WRAP);
    }

    // ==================== DES ====================

    private static String desDecrypt(String input, String step) throws Exception {
        String paramStr = step.replaceFirst("(?i)解des", "").replaceFirst("^-", "");
        String[] p = paramStr.split("-", 3);
        String keyStr  = p.length > 0 ? p[0].trim() : "";
        String ivStr   = p.length > 1 ? p[1].trim() : "";
        String modeStr = p.length > 2 ? p[2].trim() : "";
        if (modeStr.isEmpty()) modeStr = (ivStr.isEmpty() || ivStr.equals("空")) ? "DESede/ECB/PKCS5Padding" : "DESede/CBC/PKCS5Padding";

        byte[] keyBytes = resolveKeyBytes(keyStr);
        // DESede key must be 24 bytes
        if (keyBytes.length == 16) {
            byte[] k24 = new byte[24];
            System.arraycopy(keyBytes, 0, k24, 0, 16);
            System.arraycopy(keyBytes, 0, k24, 16, 8);
            keyBytes = k24;
        }
        byte[] ivBytes     = (ivStr.isEmpty() || ivStr.equals("空")) ? null : resolveKeyBytes(ivStr);
        byte[] cipherBytes = Base64.decode(input, Base64.DEFAULT);

        Cipher cipher = Cipher.getInstance(modeStr);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "DESede");
        if (ivBytes != null) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
        }
        return new String(cipher.doFinal(cipherBytes), "UTF-8");
    }

    private static String desEncrypt(String input, String step) throws Exception {
        String paramStr = step.replaceFirst("(?i)des", "").replaceFirst("^-", "");
        String[] p = paramStr.split("-", 3);
        String keyStr  = p.length > 0 ? p[0].trim() : "";
        String ivStr   = p.length > 1 ? p[1].trim() : "";
        String modeStr = p.length > 2 ? p[2].trim() : "";
        if (modeStr.isEmpty()) modeStr = (ivStr.isEmpty() || ivStr.equals("空")) ? "DESede/ECB/PKCS5Padding" : "DESede/CBC/PKCS5Padding";

        byte[] keyBytes = resolveKeyBytes(keyStr);
        if (keyBytes.length == 16) {
            byte[] k24 = new byte[24];
            System.arraycopy(keyBytes, 0, k24, 0, 16);
            System.arraycopy(keyBytes, 0, k24, 16, 8);
            keyBytes = k24;
        }
        byte[] ivBytes = (ivStr.isEmpty() || ivStr.equals("空")) ? null : resolveKeyBytes(ivStr);

        Cipher cipher = Cipher.getInstance(modeStr);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "DESede");
        if (ivBytes != null) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        }
        return Base64.encodeToString(cipher.doFinal(input.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    // ==================== RC4 ====================

    private static String rc4(String input, String key) throws Exception {
        byte[] k = key.getBytes("UTF-8");
        byte[] d = input.getBytes("UTF-8");
        byte[] s = new byte[256];
        for (int i = 0; i < 256; i++) s[i] = (byte) i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + (s[i] & 0xFF) + (k[i % k.length] & 0xFF)) & 0xFF;
            byte tmp = s[i]; s[i] = s[j]; s[j] = tmp;
        }
        int ii = 0; j = 0;
        byte[] out = new byte[d.length];
        for (int x = 0; x < d.length; x++) {
            ii = (ii + 1) & 0xFF;
            j  = (j + (s[ii] & 0xFF)) & 0xFF;
            byte tmp = s[ii]; s[ii] = s[j]; s[j] = tmp;
            out[x] = (byte) (d[x] ^ s[(s[ii] & 0xFF + s[j] & 0xFF) & 0xFF]);
        }
        return new String(out, "UTF-8");
    }

    // ==================== 替換 ====================

    private static String handleReplace(String input, String step) {
        // 多個替換用 ♯ 分隔
        String[] pairs = step.split("♯");
        String result = input;
        for (String pair : pairs) {
            if (!pair.contains(">>")) continue;
            int idx = pair.indexOf(">>");
            String from = pair.substring(0, idx);
            String to   = pair.substring(idx + 2);
            // 支持 /正則/g 格式
            if (from.startsWith("/") && from.endsWith("/g")) {
                String regex = from.substring(1, from.length() - 2);
                if (to.startsWith("/") && to.endsWith("/g")) to = to.substring(1, to.length() - 2);
                result = result.replaceAll(regex, to.equals("空") ? "" : to);
            } else {
                String toStr = to.equals("空") ? "" : to.replace("\\n", "\n").replace("\\空", "\\");
                // 通配符 * 替換
                if (from.contains("(*)")) {
                    result = result.replaceAll(Pattern.quote(from).replace("\\Q(*)\\E", ".*?"), toStr);
                } else {
                    result = result.replace(from.replace("\\n", "\n"), toStr);
                }
            }
        }
        return result;
    }

    // ==================== 分割截取 ====================

    /**
     * 格式：分割符-截取規則-合并符
     * 截取規則内聯實現（不依賴 KaiGeEngine，避免循環依賴）
     * 支持：a&&b 截取、j:path JSON路徑、@attr 屬性、固定值
     */
    private static String splitExtract(String input, String params) {
        String[] p = params.split("-", 3);
        if (p.length < 2) return input;
        String sep     = p[0].replace("\\n", "\n");
        String rule    = p[1];
        String joinSep = p.length > 2 ? p[2] : sep;
        if (joinSep.equals("同")) joinSep = sep;
        if (joinSep.equals("空")) joinSep = "";

        String[] parts = input.split(Pattern.quote(sep), -1);
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            String extracted = inlineExtract(part.trim(), rule);
            if (!TextUtils.isEmpty(extracted)) {
                if (sb.length() > 0) sb.append(joinSep);
                sb.append(extracted);
            }
        }
        return sb.toString();
    }

    /**
     * 內聯截取：支持 &&、j:、@attr、正則、固定值
     * 不依賴 KaiGeEngine，避免循環依賴
     */
    private static String inlineExtract(String content, String rule) {
        if (TextUtils.isEmpty(rule) || TextUtils.isEmpty(content)) return "";
        try {
            // j: JSON 路徑
            if (rule.startsWith("j:")) {
                String path = rule.substring(2).trim();
                org.json.JSONObject obj = new org.json.JSONObject(content.replace("\\/", "/"));
                Object cur = obj;
                for (String key : path.split("\\.")) {
                    if (cur instanceof org.json.JSONObject) {
                        cur = ((org.json.JSONObject) cur).opt(key);
                    } else break;
                }
                return cur != null ? cur.toString() : "";
            }
            // @attr 屬性截取
            if (rule.contains("@")) {
                String[] ap = rule.split("@");
                String attr = ap[ap.length - 1].trim();
                Matcher m = Pattern.compile(attr + "\\s*=\\s*[\"']([^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(content);
                return m.find() ? m.group(1).trim() : "";
            }
            // /regex/g
            if (rule.startsWith("/") && rule.endsWith("/g")) {
                Matcher m = Pattern.compile(rule.substring(1, rule.length() - 2), Pattern.DOTALL).matcher(content);
                return m.find() ? (m.groupCount() > 0 ? m.group(1) : m.group(0)).trim() : "";
            }
            // a&&b 截取（支持通配符 *）
            if (rule.contains("&&")) {
                String[] sides = rule.split("&&", 2);
                String start = sides[0].trim();
                String end   = sides[1].trim();
                if (start.contains("*")) {
                    String regex = Pattern.quote(start).replace("*", "\\E.*?\\Q")
                        + "(.*?)" + (end.isEmpty() ? "$" : Pattern.quote(end));
                    Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(content);
                    return m.find() ? m.group(1).trim() : "";
                } else {
                    int s = content.indexOf(start);
                    if (s > -1) {
                        s += start.length();
                        if (end.isEmpty()) return content.substring(s).trim();
                        int e = content.indexOf(end, s);
                        if (e > -1) return content.substring(s, e).trim();
                    }
                    return "";
                }
            }
        } catch (Exception e) { return ""; }
        return content;
    }

    // ==================== 計算 ====================

    private static String calculate(String input, boolean round, boolean decimal) {
        try {
            // 簡單四則運算，使用腳本引擎避免依賴
            // 去除空格，標準化表達式
            String expr = input.trim().replace("\\+", "+");
            double result = evalMath(expr);
            if (decimal) return String.format("%.2f", result);
            if (round) return String.valueOf(Math.round(result));
            return String.valueOf((long) result);
        } catch (Exception e) { return input; }
    }

    private static double evalMath(String expr) {
        expr = expr.trim();
        // 遞歸下降解析器：加減
        int i = findLowestPrecedenceOp(expr, "+-");
        if (i >= 0) {
            double left  = evalMath(expr.substring(0, i));
            char   op    = expr.charAt(i);
            double right = evalMath(expr.substring(i + 1));
            return op == '+' ? left + right : left - right;
        }
        // 乘除取余
        i = findLowestPrecedenceOp(expr, "*/%");
        if (i >= 0) {
            double left  = evalMath(expr.substring(0, i));
            char   op    = expr.charAt(i);
            double right = evalMath(expr.substring(i + 1));
            if (op == '*') return left * right;
            if (op == '/') return left / right;
            return left % right;
        }
        // 括號
        if (expr.startsWith("(") && expr.endsWith(")")) return evalMath(expr.substring(1, expr.length() - 1));
        return Double.parseDouble(expr);
    }

    private static int findLowestPrecedenceOp(String expr, String ops) {
        int depth = 0;
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (depth == 0 && ops.indexOf(c) >= 0 && i > 0) return i;
        }
        return -1;
    }

    // ==================== ASCII ====================

    private static String handleAscii(String input, String step) {
        try {
            boolean decode = step.startsWith("解");
            String sep = step.contains("-") ? step.substring(step.indexOf("-") + 1) : ",";
            if (decode) {
                // 解碼：把數字序列還原成字符串
                String[] nums = input.split(Pattern.quote(sep));
                StringBuilder sb = new StringBuilder();
                for (String n : nums) sb.append((char) Integer.parseInt(n.trim()));
                return sb.toString();
            } else {
                // 編碼：把字符串轉成數字序列
                StringBuilder sb = new StringBuilder();
                for (char c : input.toCharArray()) {
                    if (sb.length() > 0) sb.append(sep);
                    sb.append((int) c);
                }
                return sb.toString();
            }
        } catch (Exception e) { return input; }
    }

    // ==================== 異或 ====================

    private static String xorString(String input, String key) {
        try {
            char[] inputChars = input.toCharArray();
            char[] keyChars   = key.toCharArray();
            char[] result     = new char[inputChars.length];
            for (int i = 0; i < inputChars.length; i++) {
                result[i] = (char) (inputChars[i] ^ keyChars[i % keyChars.length]);
            }
            return new String(result);
        } catch (Exception e) { return input; }
    }

    // ==================== 字符映射 ====================

    private static String charMap(String input, String srcChars, String dstChars) {
        String[] src = srcChars.split("\\|");
        String[] dst = dstChars.split("\\|");
        String result = input;
        for (int i = 0; i < src.length && i < dst.length; i++) {
            result = result.replace(src[i], dst[i]);
        }
        return result;
    }

    // ==================== 隨機字符 ====================

    private static String randomChar(String input, String step) {
        try {
            String params  = step.substring(step.indexOf("-") + 1);
            boolean unique = params.endsWith("-唯一");
            if (unique) params = params.substring(0, params.length() - 3);
            int count = Integer.parseInt(params.trim());
            if (unique) {
                java.util.List<Character> chars = new java.util.ArrayList<>();
                for (char c : input.toCharArray()) chars.add(c);
                java.util.Collections.shuffle(chars);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(count, chars.size()); i++) sb.append(chars.get(i));
                return sb.toString();
            } else {
                StringBuilder sb = new StringBuilder();
                java.util.Random rand = new java.util.Random();
                for (int i = 0; i < count; i++) sb.append(input.charAt(rand.nextInt(input.length())));
                return sb.toString();
            }
        } catch (Exception e) { return input; }
    }

    // ==================== 時間格式化 ====================

    private static String formatDuration(String input, String sep) {
        try {
            // 嘗試解析 "秒數" 或 "分:秒" 格式
            String[] parts = input.split(":");
            long totalSeconds;
            if (parts.length == 1) {
                totalSeconds = Long.parseLong(parts[0].trim());
            } else {
                totalSeconds = Long.parseLong(parts[0].trim()) * 60 + Long.parseLong(parts[1].trim());
            }
            long h = totalSeconds / 3600;
            long m = (totalSeconds % 3600) / 60;
            long s = totalSeconds % 60;
            if (sep.isEmpty()) {
                return String.format("%d時%02d分%02d秒", h, m, s);
            } else {
                return String.format("%d%s%02d%s%02d", h, sep, m, sep, s);
            }
        } catch (Exception e) { return input; }
    }

    // ==================== Unicode 解碼 ====================

    private static String unicodeDecode(String input) {
        try {
            Pattern p = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
            Matcher m = p.matcher(input);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, String.valueOf((char) Integer.parseInt(m.group(1), 16)));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Exception e) { return input; }
    }

    // ==================== 哈希 ====================

    private static String md5(String input, boolean upper) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            String hex = bytesToHex(digest, upper);
            return hex;
        } catch (Exception e) { return input; }
    }

    private static String sha(String input, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            return bytesToHex(digest, false);
        } catch (Exception e) { return input; }
    }

    // ==================== 工具函數 ====================

    private static byte[] resolveKeyBytes(String keyStr) throws Exception {
        if (keyStr.startsWith("b64:")) {
            return Base64.decode(keyStr.substring(4), Base64.DEFAULT);
        }
        // 如果是 hex 格式（純十六進制且長度偶數）
        if (keyStr.matches("[0-9a-fA-F]+") && keyStr.length() % 2 == 0 && keyStr.length() >= 32) {
            return hexToBytes(keyStr);
        }
        return keyStr.getBytes("UTF-8");
    }

    private static String bytesToHex(byte[] bytes, boolean upper) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(upper ? h.toUpperCase() : h);
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static int tryInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String stripParens(String s) {
        s = s.trim();
        if (s.startsWith("(") && s.endsWith(")")) return s.substring(1, s.length() - 1).trim();
        return s;
    }

    private static int findChinese(String s, String keyword) {
        return s.indexOf(keyword);
    }

    /**
     * 按 # 分割工具步驟，但跳過括號內的 #
     */
    private static String[] splitToolSteps(String toolChain) {
        java.util.List<String> steps = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < toolChain.length(); i++) {
            char c = toolChain.charAt(i);
            if (c == '(' || c == '（') depth++;
            else if (c == ')' || c == '）') depth--;
            else if (c == '#' && depth == 0) {
                steps.add(toolChain.substring(start, i));
                start = i + 1;
            }
        }
        steps.add(toolChain.substring(start));
        return steps.toArray(new String[0]);
    }
}
