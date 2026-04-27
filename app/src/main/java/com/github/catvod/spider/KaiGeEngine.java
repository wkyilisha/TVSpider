package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64; 
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;

/**
 * 凱哥標準規則引擎 1.0 (正式修正版)
 * 修復：編譯錯誤及漏掉的括號、內部類、自動補全方法
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

public static ExtractionResult doExtract(String html, String rule, String host) {
    ExtractionResult result = new ExtractionResult();
    if (isEmpty(html) || isEmpty(rule)) return result;

    // 🚀 1. 指令拆分 (;; 分隔)
    String[] segments = rule.split("\\s*;;\\s*");
    String coreLogic = segments[0].trim();

    // --- 🚀 關鍵修改點 A：符號轉換 (支持 * 和 +) ---
    // 將你習慣的 * 和 + 在進入處理前，自動轉為引擎認識的 > 
    if (coreLogic.contains("*") || coreLogic.contains("+")) {
        coreLogic = coreLogic.replace("*", " > ").replace("+", " > ");
    }

    for (int i = 1; i < segments.length; i++) {
        String tag = segments[i].trim();
        if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
        if (tag.matches("\\[\\d+\\]")) {
            result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
        }
        if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
        if (tag.startsWith("[排除:")) result.excludeKey = tag.substring(4, tag.length() - 1);
    }

    // 2. 處理核心邏輯 (現在 coreLogic 裡的 * 和 + 已經變成 > 了)
    String finalValue = "";
    if (coreLogic.contains(">")) {
        String[] steps = coreLogic.split("\\s*>\\s*");
        finalValue = html; 
        for (String step : steps) {
            finalValue = processStep(finalValue, step.trim(), host);
        }
    } else {
        finalValue = processStep(html, coreLogic, host);
    }

    // --- 🚀 關鍵修改點 B：自動解碼 (支持 [base64] 和 [url_decode]) ---
    if (!isEmpty(finalValue)) {
        // Base64 解碼處理
        if (rule.contains("[base64]")) {
            try {
                finalValue = new String(android.util.Base64.decode(finalValue, android.util.Base64.DEFAULT));
            } catch (Exception e) {}
        }
        // URL 解碼處理
        if (rule.contains("[url_decode]")) {
            try {
                finalValue = java.net.URLDecoder.decode(finalValue, "UTF-8");
            } catch (Exception e) {}
        }
    }

    // 3. 過濾與補全
    if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) finalValue = "";
    if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) finalValue = "";
    if (result.shouldFull && !isEmpty(finalValue)) finalValue = autoFullUrl(finalValue, host);

    result.value = finalValue;
    return result;
}

    private static String processStep(String content, String step, String host) {
        if (step.equalsIgnoreCase("[base64]")) {
            try { return new String(Base64.decode(content, Base64.DEFAULT)); } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("[url_decode]")) {
            try { return URLDecoder.decode(content, "UTF-8"); } catch (Exception e) { return content; }
        }
        if (step.startsWith("[reg:")) {
            Matcher m = Pattern.compile(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }
        if (step.contains(" + ")) return handleCombination(content, step, host);

        return executeSingleRule(content, step);
    }

    private static String executeSingleRule(String html, String rule) {
        if (rule.contains("@")) {
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim(); 
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1).trim();
            return ""; 
        }

        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html; 
    }

    private static String handleCombination(String html, String logic, String host) {
        // 🚀 1. 按照 + 號拆分
        String[] parts = logic.split("\\+");
        StringBuilder sb = new StringBuilder();
        
        for (String p : parts) {
            String item = p.trim(); // 👈 先去掉前後空格，不要動引號
            
            // 🚀 2. 只有當這一段完全是被引號包住的（如 "www.qkw1.com"），才去掉引號當作「純文字」
            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                sb.append(item.substring(1, item.length() - 1));
            } 
            // 🚀 3. 如果包含提取符號，則原封不動地交給提取器，保留裡面的引號
            else if (item.contains("@") || item.contains("&&")) {
                sb.append(executeSingleRule(html, item));
            } 
            // 🚀 4. 其他情況（沒引號也沒規則），當作普通文字直接拼入
            else {
                sb.append(item);
            }
        }
        return sb.toString();
    }


    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            String regexStart = Pattern.quote(startRule).replace("*", "\\E.*?\\Q");
            String fullRegex = regexStart + "(.*?)" + (isEmpty(end) ? "$" : Pattern.quote(end));
            Pattern pattern = Pattern.compile(fullRegex, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            return matcher.find() ? matcher.group(1).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private static String simpleCut(String html, String start, String end) {
        try {
            int s = html.indexOf(start);
            if (s > -1) {
                s += start.length();
                if (isEmpty(end)) return html.substring(s).trim();
                int e = html.indexOf(end, s);
                if (e > -1) return html.substring(s, e).trim();
            }
        } catch (Exception e) { return ""; }
        return "";
    }

    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) {
            if (host.endsWith("/")) return host + path.substring(1);
            return host + path;
        }
        return host + (host.endsWith("/") ? "" : "/") + path;
    }

    // 🚀 核心內部類，確保 KG.java 能讀到結果
    public static class ExtractionResult {
        public String value = "";
        public boolean shouldFull = false;
        public int index = 0;
        public String includeKey = "";
    }
}