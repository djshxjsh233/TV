package com.xlz.android.tv.player.exo;

import androidx.annotation.NonNull;

import com.xlz.android.tv.player.media.M3U8;
import com.xlz.android.tv.setting.Setting;

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
 *
 * 安全规则 (经过多轮线上 bug 修复总结):
 * 1. 带 Range 的请求(部分内容)不净化; 非 200 响应不净化
 * 2. 只处理 m3u8 清单 (URL 含 .m3u8 或 Content-Type mpegurl, 且 body 以 #EXTM3U 开头)
 * 3. master playlist (含 #EXT-X-STREAM-INF) 不净化, 只净化单层 media playlist
 * 4. 读 body 用 bytes() 拿原始字节; 净化无变化时按原字节重建 (Content-Length=字节数, 不动 Content-Encoding),
 *    保证 media3 OkHttpDataSource 读取的字节与原始响应完全一致
 * 5. 只有确实剔除了广告分片才替换内容
 */
public class M3u8AdInterceptor implements Interceptor {

    public static volatile boolean enabled = true;

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);
        if (!response.isSuccessful()) {
            android.util.Log.e("M3u8Ad", "非2xx响应: " + request.url() + " code=" + response.code());
            return response;
        }
        if (!enabled || !Setting.isAdblock()) return response;
        try {
            // 带 Range 的请求(部分内容)不净化, 避免 Content-Length/Content-Range 错乱
            if (request.header("Range") != null) return response;
            // 非 200 完整响应(如 206 部分内容)不净化
            if (response.code() != 200) return response;
            String url = request.url().toString();
            // 本地代理请求 (已净化内容) 直接放行, 避免二次处理
            if (url.contains("127.0.0.1") || url.contains("localhost")) return response;
            String contentType = response.header("Content-Type");
            boolean isM3u8 = url.contains(".m3u8") || (contentType != null && contentType.contains("mpegurl"));
            if (!isM3u8) return response;
            ResponseBody body = response.body();
            if (body == null) return response;
            MediaType mediaType = body.contentType();
            byte[] bytes = body.bytes();
            // 非 m3u8 文本: 原字节重建返回
            if (bytes.length < 7 || bytes[0] != '#' || bytes[1] != 'E' || bytes[2] != 'X' || bytes[3] != 'T') {
                return rebuild(response, bytes, mediaType);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            // master playlist (多码率子清单) 不净化, 避免误伤子流 URL
            if (content.contains("#EXT-X-STREAM-INF")) return rebuild(response, bytes, mediaType);
            // 传目录 URL (以 / 结尾) 给 purify, 保证相对路径分片/KEY 拼接正确
            String base = toDirectoryUrl(url);
            String purified = M3U8.purify(base, content);
            if (purified == null || purified.equals(content)) return rebuild(response, bytes, mediaType);
            android.util.Log.e("M3u8Ad", "净化广告: " + url + " 分片 " + countTs(content) + "->" + countTs(purified));
            return rebuild(response, purified.getBytes(StandardCharsets.UTF_8), mediaType);
        } catch (Exception e) {
            android.util.Log.e("M3u8Ad", "拦截器异常: " + e);
            return response;
        }
    }

    private static int countTs(String s) {
        int c = 0;
        for (String line : s.split("\n")) if (line.contains(".ts")) c++;
        return c;
    }

    /** 用新字节重建响应体 (原 body 已被消费, 必须重建; Content-Length=实际字节数) */
    private static Response rebuild(Response response, byte[] bytes, MediaType mediaType) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        ResponseBody newBody = ResponseBody.create(content, mediaType);
        return response.newBuilder()
                .body(newBody)
                .header("Content-Length", String.valueOf(bytes.length))
                .build();
    }

    /** 把 m3u8 完整 URL 转成目录 URL (以 / 结尾), 供 purify 拼接相对路径分片/KEY */
    private static String toDirectoryUrl(String url) {
        int idx = url.lastIndexOf('/');
        return idx > 0 ? url.substring(0, idx + 1) : url;
    }
}
