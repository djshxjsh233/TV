package com.xlz.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;

import com.xlz.android.tv.App;
import com.xlz.android.tv.BuildConfig;
import com.xlz.android.tv.player.engine.PlayerEngine;
import com.xlz.android.tv.player.track.LangUtil;
import com.xlz.android.tv.setting.DecodeSetting;
import com.xlz.android.tv.setting.PlayerSetting;
import com.xlz.android.tv.setting.SpeedSetting;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static ExoPlayer buildPlayer(Player.Listener listener, DefaultPreloadManager.Builder preloadManagerBuilder) {
        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(App.get()).setSkipSilenceEnabled(SpeedSetting.isSkipSilence());
        ExoPlayer player = preloadManagerBuilder.buildExoPlayer(playerBuilder);
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    public static String getMimeType(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED || errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return MimeTypes.APPLICATION_M3U8;
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return MimeTypes.APPLICATION_M3U8;
        return null;
    }

    static LoadControl buildLoadControl(int maxPreloadBufferBytes) {
        int buffer = PlayerSetting.getBuffer();
        return new DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, maxPreloadBufferBytes).build();
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    static DefaultTrackSelector buildTrackSelector(int decode) {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (DecodeSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled());
        builder.setForceHighestSupportedBitrate(true);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    private static boolean isAudioSoftwareDecode(int decode) {
        return decode == PlayerEngine.SOFT;
    }

    static RenderersFactory buildRenderersFactory(int decode) {
        return buildRenderersFactory(decode, null);
    }

    static RenderersFactory buildRenderersFactory(int decode, AudioProcessor audioProcessor) {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioProcessor);
            }
        };
        // 软解模式: FFmpeg(media3-decoder-ffmpeg) 音频软解优先; 硬解模式: MediaCodec 优先, FFmpeg 自动兜底
        int extensionMode = isAudioSoftwareDecode(decode) ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
        return factory.setEnableDecoderFallback(true).setExtensionRendererMode(extensionMode);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, AudioProcessor audioProcessor) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (!DecodeSetting.isAudioPassThrough()) builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
        if (audioProcessor != null) builder.setAudioProcessors(new AudioProcessor[]{audioProcessor});
        return builder.build();
    }
}
