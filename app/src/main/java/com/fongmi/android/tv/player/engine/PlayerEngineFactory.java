package com.fongmi.android.tv.player.engine;

import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.EXO;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;

public final class PlayerEngineFactory {

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return create(decode, resolve(), listener);
    }

    public static PlayerEngine create(int decode, PlaySpec spec, Player.Listener listener) {
        return create(decode, resolve(spec), listener);
    }

    public static PlayerEngine create(int decode, PlayerEngine.Type type, Player.Listener listener) {
        return new ExoPlayerEngine(decode, listener);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == EXO;
    }

    private static PlayerEngine.Type resolve(PlaySpec spec) {
        return EXO;
    }

    private static PlayerEngine.Type resolve() {
        return EXO;
    }
}
