package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * m3u8 净化内容本地代理
 * 播放器请求 /m3u8 时返回内存中缓存的净化后清单 (TVBox RemoteServer 同款方案)。
 * 净化在播放前完成并缓存, 播放器请求本地代理秒回, 不经过源站, 不卡顿。
 */
public class M3u8 implements Process {

    /** 内存缓存: 净化后的 m3u8 内容 (null 表示未设置/无净化) */
    public static volatile String content;

    public static boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public static void setContent(String m3u8) {
        content = m3u8;
    }

    public static void clear() {
        content = null;
    }

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/m3u8");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (hasContent()) {
            Response response = Nano.ok(content);
            response.setMimeType("application/vnd.apple.mpegurl");
            return response;
        }
        return Nano.error("m3u8 content empty");
    }
}
