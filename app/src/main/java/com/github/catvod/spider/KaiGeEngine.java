package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.security.MessageDigest;
import java.util.HashMap;

/**
 * 凱哥標準規則引擎 3.0
 *
 * 新增：
 *   - || 規則級輪詢：逐個嘗試直到非空
 *   - {{變量名}} 替換：從 varCache 取值
 *   - [工具:xxx] 工具鏈接入 KaiGeTool
 *   - j: JSON 路径增強（數組下標 [n]、負數、範圍 [1,-1]、* 通配）
 *   - p: Jsoup 選擇器模式
 *   - XPath 獨立分支（不被 finalValue 覆蓋）
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    // ==================== 主入口（帶變量緩存） ====================

    public static ExtractionResult doExtract(String html, String rule, String host) {
        return doExtract(html, rule, host, new HashMap<String, String>(), "", null);
    }

    public static ExtractionResult doExtract(String html, String rule, String host,
                                              Map<String, String> varCache,
                                              String lastHtml,
                                              org.json.JSONObject ruleJson) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        // 1. 替換 {{變量名}}
        rule = replaceVars(rule, varCache);

        // 2. 指令拆分 (;; 分隔)
        String[] segments = rule.split("\\s*;;\\s*");
        String coreLogic  = segments[0].trim();

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[full]"))       result.shouldFull = true;
            if (tag.matches("\\[\\d+\\]"))             result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            if (tag.startsWith("[包含:"))               result.includeKey = tag.substring(4, tag.length() - 1);
            if (tag.startsWith("[排除:"))               result.excludeKey = tag.substring(4, tag.length() - 1);
        }

        // 3. || 規則級輪詢：按 || 分割，逐個嘗試直到非空
        if (coreLogic.contains("||")) {
            String[] alternatives = coreLogic.split("\\|\\|");
            for (String alt : alternatives) {
                alt = alt.trim();
                if (isEmpty(alt)) continue;
                String val = executeCoreLogic(html, alt, host, varCache, lastHtml, ruleJson);
                if (!isEmpty(val)) {
                    result.value = applyFilters(val, result);
                    if (result.shouldFull && !isEmpty(result.value)) result.value = autoFullUrl(result.value, host);
                    return result;
                }
            }
            result.value = "";
            return result;
        }

        // 4. 單規則執行
        String finalValue = executeCoreLogic(html, coreLogic, host, varCache, lastHtml, ruleJson);
        finalValue = applyFilters(finalValue, result);
        if (result.shouldFull && !isEmpty(finalValue)) finalValue = autoFullUrl(finalValue, host);
        result.value = finalValue;
        return result;
    }

    // ==================== 核心邏輯執行 ====================

    private static String executeCoreLogic(String html, String logic, String host,
                                            Map<String, String> varCache,
                                            String lastHtml,
                                            org.json.JSONObject ruleJson) {
        logic = logic.trim();

        // XPath 獨立分支
        if (logic.startsWith("xpath:")) {
            return executeXPath(html, logic.substring(6).trim());
        }

        // p: Jsoup 選擇器
        if (logic.startsWith("p:")) {
            return executeJsoup(html, logic.substring(2).trim());
        }

        // j: JSON 路徑增強
        if (logic.startsWith("j:")) {
            return executeJsonPath(html, logic.substring(2).trim());
        }

        // > 鏈式步驟
        if (logic.contains(">")) {
            // 避免把 j:data.list[1,-1] 裡的 > 當鏈式符
            // 只在方括號外的 > 才作為鏈式符
            String[] steps = splitByChainOp(logic);
            if (steps.length > 1) {
                String current = html;
                for (String step : steps) {
                    current = processStep(current, step.trim(), host, varCache, lastHtml, ruleJson);
                }
                return current;
            }
        }

        return processStep(html, logic, host, varCache, lastHtml, ruleJson);
    }

    // ==================== 單步處理 ====================

    private static String processStep(String content, String step, String host,
                                       Map<String, String> varCache,
                                       String lastHtml,
                                       org.json.JSONObject ruleJson) {
        if (isEmpty(step)) return content;

        // [工具:xxx] — 工具鏈
        if (step.startsWith("[工具:") && step.endsWith("]")) {
            String toolChain = step.substring(4, step.length() - 1);
            return KaiGeTool.process(content, toolChain, host, varCache, lastHtml, ruleJson);
        }

        // j: JSON 路徑
        if (step.startsWith("j:") || step.startsWith("json:") || step.contains("json:")) {
            String path = step.replace("j:", "").replace("[", "").replace("]", "").replace("json:", "").trim();
            return executeJsonPath(content, path);
        }

        // p: Jsoup
        if (step.startsWith("p:")) {
            return executeJsoup(content, step.substring(2).trim());
        }

        // Base64
        if (step.equalsIgnoreCase("[base64]") || step.equalsIgnoreCase("解b64")) {
            try { return new String(Base64.decode(content, Base64.DEFAULT), "UTF-8"); } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("b64")) {
            return Base64.encodeToString(content.getBytes(), Base64.NO_WRAP);
        }

        // URL 编解码
        if (step.equalsIgnoreCase("[url_decode]") || step.equalsIgnoreCase("解url")) {
            try { return java.net.URLDecoder.decode(content, "UTF-8"); } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("url")) {
            try { return java.net.URLEncoder.encode(content, "UTF-8"); } catch (Exception e) { return content; }
        }

        // 正則
        if (step.startsWith("[reg:") && step.endsWith("]")) {
            Matcher m = Pattern.compile(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }
        // /正則/g 格式
        if (step.startsWith("/") && step.endsWith("/g")) {
            try {
                Pattern p = Pattern.compile(step.substring(1, step.length() - 2), Pattern.DOTALL);
                Matcher m = p.matcher(content);
                return m.find() ? (m.groupCount() > 0 ? m.group(1) : m.group(0)).trim() : "";
            } catch (Exception e) { return ""; }
        }

        // [提取:xxx]
        if (step.startsWith("[提取:") && step.endsWith("]")) {
            return executeSingleRule(content, step.substring(4, step.length() - 1));
        }

        // [替換:a>>b]
        if (step.startsWith("[替换:") && step.endsWith("]") || step.startsWith("[替換:") && step.endsWith("]")) {
            try {
                String params = step.substring(4, step.length() - 1);
                int arrow = params.indexOf(">>");
                if (arrow > -1) {
                    String oldStr = params.substring(0, arrow);
                    String newStr = params.substring(arrow + 2).equals("空") ? "" : params.substring(arrow + 2);
                    return content.replace(oldStr, newStr);
                }
            } catch (Exception e) { return content; }
        }

        // [排序:1>3>5]
        if (step.startsWith("[排序:") && step.endsWith("]")) {
            try {
                String[] order = step.substring(4, step.length() - 1).split(">");
                int idx = content.indexOf("?");
                String base = idx > -1 ? content.substring(0, idx + 1) : "";
                String query = idx > -1 ? content.substring(idx + 1) : content;
                String[] pairs = query.split("&");
                StringBuilder sb = new StringBuilder(base);
                for (String o : order) {
                    int pos = Integer.parseInt(o.trim()) - 1;
                    if (pos >= 0 && pos < pairs.length) {
                        if (sb.length() > base.length()) sb.append("&");
                        sb.append(pairs[pos]);
                    }
                }
                return sb.toString();
            } catch (Exception e) { return content; }
        }

        // 時間戳
        if (step.equalsIgnoreCase("[time]"))   return String.valueOf(System.currentTimeMillis() / 1000);
        if (step.equalsIgnoreCase("[time13]")) return String.valueOf(System.currentTimeMillis());

        // MD5 / SHA
        if (step.equalsIgnoreCase("[md5]") || step.equals("md5")) {
            try {
                MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] d = md.digest(content.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : d) { String h = Integer.toHexString(b & 0xFF); if (h.length() == 1) sb.append('0'); sb.append(h); }
                return sb.toString();
            } catch (Exception e) { return content; }
        }

        // AES CBC
        if (step.startsWith("[aes_cbc:") && step.endsWith("]")) {
            try {
                String[] p = step.substring(9, step.length() - 1).split(",");
                return com.github.catvod.utils.AESEncryption.decrypt(content, p[0].trim(), p.length > 1 ? p[1].trim() : "", com.github.catvod.utils.AESEncryption.CBC_PKCS_7_PADDING);
            } catch (Exception e) { return ""; }
        }
        // AES ECB
        if (step.startsWith("[aes_ecb:") && step.endsWith("]")) {
            try {
                return com.github.catvod.utils.AESEncryption.decrypt(content, step.substring(9, step.length() - 1).trim(), "", com.github.catvod.utils.AESEncryption.ECB_PKCS_7_PADDING);
            } catch (Exception e) { return ""; }
        }

        // + 拼接
        if (step.contains("+")) {
            return handleCombination(content, step, host);
        }

        return executeSingleRule(content, step);
    }

    // ==================== JSON 路徑增強（j:） ====================

    /**
     * 支持：
     *   data.list[1].name      — 第1個元素（自然數從1開始）
     *   data.list[-1].name     — 倒數第1個
     *   data.list[1,-1]        — 範圍數組
     *   data.list              — 完整數組
     *   urls.*                 — 所有 key 的值作為數組
     */
    private static String executeJsonPath(String json, String path) {
        try {
            json = json.replace("\\/", "/").trim();
            // 嘗試修復非標 JSON
            Object root;
            if (json.startsWith("[")) root = new org.json.JSONArray(json);
            else root = new org.json.JSONObject(json);

            return resolveJsonPath(root, path);
        } catch (Exception e) {
            // 暴力正則兜底
            String keyName = path.replaceAll(".*\\.", "").replaceAll("\\[.*", "");
            Matcher m = Pattern.compile("\"" + keyName + "\"\\s*:\\s*\"?(.*?)\"?[,}]").matcher(json);
            return m.find() ? m.group(1).replace("\\/", "/").trim() : "";
        }
    }

    private static String resolveJsonPath(Object root, String path) throws Exception {
        if (isEmpty(path)) {
            if (root instanceof org.json.JSONObject) return ((org.json.JSONObject) root).toString();
            if (root instanceof org.json.JSONArray) return ((org.json.JSONArray) root).toString();
            return root != null ? root.toString() : "";
        }

        // 切出第一段 key，可能帶 [n] 或 [n,m] 或 .*
        String firstKey;
        String restPath;
        int dotIdx = path.indexOf('.');
        int braIdx = path.indexOf('[');

        if (braIdx >= 0 && (dotIdx < 0 || braIdx <= dotIdx)) {
            firstKey = path.substring(0, braIdx);
            restPath = path.substring(braIdx);
        } else if (dotIdx >= 0) {
            firstKey = path.substring(0, dotIdx);
            restPath = path.substring(dotIdx + 1);
        } else {
            firstKey = path;
            restPath = "";
        }

        // * 通配：取當前 JSONObject 所有 key 的值
        if (firstKey.equals("*") || path.equals("*")) {
            if (root instanceof org.json.JSONObject) {
                org.json.JSONObject obj = (org.json.JSONObject) root;
                org.json.JSONArray arr = new org.json.JSONArray();
                for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
                    arr.put(obj.get(it.next()));
                }
                return arr.toString();
            }
        }

        // 取當前節點的值
        Object current = null;
        if (root instanceof org.json.JSONObject) {
            if (!isEmpty(firstKey)) current = ((org.json.JSONObject) root).opt(firstKey);
            else current = root;
        } else if (root instanceof org.json.JSONArray) {
            current = root;
        } else {
            return root != null ? root.toString() : "";
        }

        if (current == null) return "";

        // 處理 [n] [n,m] 下標
        if (!isEmpty(restPath) && restPath.startsWith("[")) {
            int closeIdx = restPath.indexOf(']');
            if (closeIdx > 0) {
                String indexStr = restPath.substring(1, closeIdx);
                String afterBracket = restPath.substring(closeIdx + 1).replaceFirst("^\\.", "");

                if (indexStr.contains(",")) {
                    // 範圍 [start, end]
                    String[] range = indexStr.split(",");
                    int start = Integer.parseInt(range[0].trim());
                    int end   = Integer.parseInt(range[1].trim());
                    if (current instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) current;
                        int len = arr.length();
                        int s = start > 0 ? start - 1 : len + start;
                        int e = end > 0 ? end : len + end + 1;
                        org.json.JSONArray sub = new org.json.JSONArray();
                        for (int i = s; i < e && i < len; i++) sub.put(arr.get(i));
                        return isEmpty(afterBracket) ? sub.toString() : resolveJsonPath(sub, afterBracket);
                    }
                } else {
                    // 單個下標
                    int idx = Integer.parseInt(indexStr.trim());
                    if (current instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) current;
                        int len = arr.length();
                        int realIdx = idx > 0 ? idx - 1 : len + idx;
                        Object elem = arr.get(realIdx);
                        return isEmpty(afterBracket) ? (elem != null ? elem.toString() : "") : resolveJsonPath(elem, afterBracket);
                    }
                }
            }
        }

        // 繼續遞歸
        return isEmpty(restPath) ? (current != null ? current.toString() : "") : resolveJsonPath(current, restPath.replaceFirst("^\\.", ""));
    }

    // ==================== Jsoup 選擇器（p:） ====================

    /**
     * 格式：p:selector->attr 或 p:selector（返回 text）
     */
    private static String executeJsoup(String html, String rule) {
        try {
            String selector;
            String attr = "";
            if (rule.contains("->")) {
                String[] parts = rule.split("->", 2);
                selector = parts[0].trim();
                attr     = parts[1].trim();
            } else {
                selector = rule.trim();
            }

            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            org.jsoup.select.Elements elements = doc.select(selector);
            if (elements.isEmpty()) return "";

            if (attr.isEmpty() || attr.equals("text")) {
                // 多個元素返回 $$$ 分隔
                StringBuilder sb = new StringBuilder();
                for (org.jsoup.nodes.Element el : elements) {
                    if (sb.length() > 0) sb.append("$$$");
                    sb.append(el.text().trim());
                }
                return sb.toString();
            } else if (attr.equals("html") || attr.equals("outerHtml")) {
                StringBuilder sb = new StringBuilder();
                for (org.jsoup.nodes.Element el : elements) {
                    if (sb.length() > 0) sb.append("$$$");
                    sb.append(el.outerHtml());
                }
                return sb.toString();
            } else {
                // 屬性值，多個返回 $$$ 分隔
                StringBuilder sb = new StringBuilder();
                for (org.jsoup.nodes.Element el : elements) {
                    String val = el.attr(attr).trim();
                    if (!val.isEmpty()) {
                        if (sb.length() > 0) sb.append("$$$");
                        sb.append(val);
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) { return ""; }
    }

    // ==================== XPath ====================

    private static String executeXPath(String html, String xpathQuery) {
        try {
            org.seimicrawler.xpath.JXDocument jxDoc = org.seimicrawler.xpath.JXDocument.create(html);
            java.util.List<org.seimicrawler.xpath.JXNode> nodes = jxDoc.selN(xpathQuery);
            if (nodes == null || nodes.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (org.seimicrawler.xpath.JXNode node : nodes) {
                if (sb.length() > 0) sb.append("$$$");
                sb.append(node.asString().trim());
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ==================== && 截取 ====================

    private static String executeSingleRule(String html, String rule) {
        if (rule.contains("@")) {
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim();
            Matcher m = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            return m.find() ? m.group(1).trim() : "";
        }
        if (rule.contains("&&")) {
            String[] parts = rule.split("&&", 2);
            String start = parts[0].trim();
            String end   = parts.length > 1 ? parts[1].trim() : "";
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html;
    }

    private static String handleCombination(String html, String logic, String host) {
        String[] parts = logic.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String item = p.trim();
            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                sb.append(item.substring(1, item.length() - 1));
            } else if (item.contains("@") || item.contains("&&")) {
                sb.append(executeSingleRule(html, item));
            } else {
                sb.append(item);
            }
        }
        return sb.toString();
    }

    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            String regexStart = Pattern.quote(startRule).replace("*", "\\E.*?\\Q");
            String fullRegex  = regexStart + "(.*?)" + (isEmpty(end) ? "$" : Pattern.quote(end));
            Matcher matcher   = Pattern.compile(fullRegex, Pattern.DOTALL).matcher(html);
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

    // ==================== 過濾 ====================

    private static String applyFilters(String val, ExtractionResult result) {
        if (!isEmpty(result.includeKey) && !val.contains(result.includeKey)) return "";
        if (!isEmpty(result.excludeKey) && val.contains(result.excludeKey)) return "";
        return val;
    }

    // ==================== URL 補全 ====================

    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) return host + (host.endsWith("/") ? path.substring(1) : path);
        return host + (host.endsWith("/") ? "" : "/") + path;
    }

    // ==================== 變量替換 ====================

    public static String replaceVars(String text, Map<String, String> varCache) {
        if (isEmpty(text) || varCache == null || varCache.isEmpty()) return text;
        for (Map.Entry<String, String> entry : varCache.entrySet()) {
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return text;
    }

    // ==================== 鏈式符分割（避開 j: 路徑裡的 >） ====================

    private static String[] splitByChainOp(String logic) {
        java.util.List<String> steps = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inJ = false;
        for (int i = 0; i < logic.length(); i++) {
            char c = logic.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '>' && depth == 0) {
                steps.add(logic.substring(start, i));
                start = i + 1;
            }
        }
        steps.add(logic.substring(start));
        return steps.toArray(new String[0]);
    }

    // ==================== 結果類 ====================

    public static class ExtractionResult {
        public String value      = "";
        public boolean shouldFull = false;
        public int index          = 0;
        public String includeKey  = "";
        public String excludeKey  = "";
    }
}
