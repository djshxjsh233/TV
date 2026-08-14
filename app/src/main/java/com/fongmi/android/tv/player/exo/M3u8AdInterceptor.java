package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.player.media.M3U8;
import com.fongmi.android.tv.setting.Setting;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * m3u8 响应净化拦截器 (播放前剔广告, 丝滑不卡)
 * 挂载在播放器 HTTP 数据源上: 请求 m3u8 清单时, 先净化再返回给播放器解析。
 * 只处理 m3u8 文本清单 (URL 含 .m3u8 或 Content-Type 为 mpegurl), ts 分片等二进制不处理。
 */
public class M3u8AdInterceptor implements Interceptor {

    public static volatile boolean enabled = true;

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);
        if (!enabled || !Setting.isAdblock() || !response.isSuccessful()) return response;
        try {
            String url = request.url().toString();
            String contentType = response.header("Content-Type");
            boolean isM3u8 = url.contains(".m3u8") || (contentType != null && contentType.contains("mpegurl"));
            if (!isM3u8) return response;
            ResponseBody body = response.body();
            if (body == null) return response;
            String content = body.string();
            if (content == null || !content.startsWith("#EXTM3U")) return response;
            String purified = M3U8.purify(url, content);
            if (purified == null || purified.equals(content)) return response;
            MediaType mediaType = body.contentType();
            ResponseBody newBody = ResponseBody.create(purified, mediaType);
            return response.newBuilder().body(newBody).header("Content-Length", String.valueOf(purified.length())).build();
        } catch (Exception e) {
            return response;
        }
    }
}
