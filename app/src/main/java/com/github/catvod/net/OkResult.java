package com.github.catvod.net;

import android.text.TextUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OkResult {

    private final int code;
    private final String body;
    private final byte[] bodyBytes;
    private final Map<String, List<String>> resp;

    public OkResult() {
        this.code = 500;
        this.body = "";
        this.bodyBytes = null;
        this.resp = new HashMap<>();
    }

    public OkResult(int code, String body, Map<String, List<String>> resp) {
        this.code = code;
        this.body = body;
        this.bodyBytes = null;
        this.resp = resp;
    }

    public OkResult(int code, byte[] bodyBytes, Map<String, List<String>> resp) {
        this.code = code;
        this.body = null;
        this.bodyBytes = bodyBytes;
        this.resp = resp;
    }

    public int getCode() {
        return code;
    }

    public String getBody() {
        return TextUtils.isEmpty(body) ? "" : body;
    }

    public byte[] getBodyBytes() {
        return bodyBytes;
    }

    public Map<String, List<String>> getResp() {
        return resp;
    }
}
