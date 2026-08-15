package com.xlz.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.xlz.android.tv.api.DanmakuApi;
import com.xlz.android.tv.api.config.VodConfig;
import com.xlz.android.tv.bean.Danmaku;
import com.xlz.android.tv.bean.Episode;
import com.xlz.android.tv.bean.History;
import com.xlz.android.tv.bean.Result;
import com.xlz.android.tv.player.media.MediaItemFactory;
import com.xlz.android.tv.setting.DanmakuSetting;

import java.util.function.Consumer;

public final class VodPlaybackMedia {

    public static MediaMetadata metadata(History history, Episode episode) {
        String title = history.getVodName();
        String name = episode.getName();
        if (name.equals(title)) name = "";
        return MediaItemFactory.buildMetadata(title, name, history.getVodPic(), name);
    }

    public static void searchDanmaku(Result result, History history, Episode episode, Consumer<Danmaku> set, Consumer<Danmaku> add) {
        if (!DanmakuApi.canSearch()) return;
        if (VodConfig.get().getSite(result.getKey()).getDanmaku() == 0) return;
        DanmakuApi.search(history.getVodName(), episode.getName(), danmaku -> {
            if (DanmakuSetting.isSpiderFirst() && !result.getDanmaku().isEmpty()) add.accept(danmaku);
            else set.accept(danmaku);
        });
    }
}
