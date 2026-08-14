package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.player.media.M3U8;
import com.fongmi.android.tv.setting.Setting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * m3u8 响应净化拦截器 (播放前剔广告, 丝滑不卡)
 * 挂载在播放器 HTTP 数据源上: 请求 m3u8 清单时, 先净化再返回给播放器解析。
 * 只处理 m3u8 文本清单 (URL 含 .m3u8 或 Content-Type 为 mpegurl), ts 分片等二进制不处理。
 *
 * 安全规则:
 * 1. 带 Range 头的请求(部分内容/206)不净化, 直接放行
 * 2. 一旦调用了 body.string() 消费响应体, 无论是否净化, 都必须重建 ResponseBody 返回,
 *    否则播放器读到的 body 已被消费为空, 导致所有资源无法播放
 * 3. 重建时同步修正 Content-Length (UTF-8 字节数), 保证 media3 OkHttpDataSource 读取一致
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
            // 带 Range 的请求(部分内容)不净化, 避免 Content-Length/Content-Range 错乱
            if (request.header("Range") != null) return response;
            // 非 200 完整响应(如 206 部分内容)不净化
            if (response.code() != 200) return response;
            String url = request.url().toString();
            String contentType = response.header("Content-Type");
            boolean isM3u8 = url.contains(".m3u8") || (contentType != null && contentType.contains("mpegurl"));
            if (!isM3u8) return response;
            ResponseBody body = response.body();
            if (body == null) return response;
            MediaType mediaType = body.contentType();
            String content = body.string();
            if (content == null || content.length() == 0) return rebuild(response, content, mediaType);
            if (!content.startsWith("#EXTM3U")) return rebuild(response, content, mediaType);
            // master playlist (多码率子清单) 不净化, 避免误伤子流 URL
            if (content.contains("#EXT-X-STREAM-INF")) return rebuild(response, content, mediaType);
            String purified = M3U8.purify(url, content);
            if (purified == null || purified.equals(content)) return rebuild(response, content, mediaType);
            return rebuild(response, purified, mediaType);
        } catch (Exception e) {
            return response;
        }
    }

    /** 用新内容重建响应体 (content 非空时调用; 原 body 已被消费, 必须重建) */
    private static Response rebuild(Response response, String content, MediaType mediaType) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ResponseBody newBody = ResponseBody.create(content, mediaType);
        return response.newBuilder().body(newBody).header("Content-Length", String.valueOf(bytes.length)).build();
    }
}
