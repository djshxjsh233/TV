package com.xlz.android.tv.playback;

import com.xlz.android.tv.bean.Track;
import com.xlz.android.tv.player.PlayerManager;

public final class PlaybackReset {

    public static void afterError(PlayerManager player) {
        afterError(player, null);
    }

    public static void afterError(PlayerManager player, Runnable beforeReset) {
        if (beforeReset != null) beforeReset.run();
        Track.delete(player.getKey());
        player.resetTrack();
        player.reset();
        player.stop();
    }
}
