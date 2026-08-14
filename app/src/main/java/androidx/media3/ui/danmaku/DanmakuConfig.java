package androidx.media3.ui.danmaku;

/** 弹幕配置占位类 (弹幕功能已禁用) */
public class DanmakuConfig {

    public static final int STYLE_NONE = 0;
    public static final int STYLE_SHADOW = 1;
    public static final int STYLE_STROKE = 2;
    public static final int STYLE_PROJECTION = 3;
    public static final int COLOR_MODE_DEFAULT = 0;
    public static final int COLOR_MODE_GRADIENT = 1;
    public static final int COLOR_MODE_COLORFUL = 2;
    public static final int B = 0;

    public final float textScale = 1f;
    public final float transparency = 0.5f;
    public final boolean textBold = false;
    public final int styleMode = STYLE_STROKE;
    public final float shadowTransparency = 0.5f;
    public final float strokeWidthMultiplier = 0.1f;
    public final float projectionOffsetXMultiplier = 0.05f;
    public final float projectionOffsetYMultiplier = 0.05f;
    public final float projectionTransparency = 0.5f;
    public final int colorMode = COLOR_MODE_DEFAULT;
    public final long durationMs = 5000L;
    public final long fixedDurationMs = 4000L;
    public final long timeOffsetMs = 0L;
    public final int maxOnScreen = 100;
    public final float scrollAreaRatio = 1f;
    public final float scrollGapRatio = 1f;
    public final float lineSpacing = 1.5f;
    public final int maxScrollLines = 20;
    public final int maxTopLines = 10;
    public final int maxBottomLines = 10;
    public final boolean showScroll = true;
    public final boolean showTop = true;
    public final boolean showBottom = true;
    public final boolean showReverse = true;
    public final boolean showPositioned = true;
    public final boolean showSubtitle = true;
    public final boolean showSpecial = true;

    public static final DanmakuConfig DEFAULT = new DanmakuConfig();

    private DanmakuConfig() {}

    public static final class Builder {
        public Builder setTextScale(float v) { return this; }
        public Builder setTransparency(float v) { return this; }
        public Builder setTextBold(boolean v) { return this; }
        public Builder setStyleMode(int v) { return this; }
        public Builder setShadowTransparency(float v) { return this; }
        public Builder setStrokeWidthMultiplier(float v) { return this; }
        public Builder setProjectionOffsetXMultiplier(float v) { return this; }
        public Builder setProjectionOffsetYMultiplier(float v) { return this; }
        public Builder setProjectionTransparency(float v) { return this; }
        public Builder setColorMode(int v) { return this; }
        public Builder setDurationMs(long v) { return this; }
        public Builder setFixedDurationMs(long v) { return this; }
        public Builder setTimeOffsetMs(long v) { return this; }
        public Builder setMaxOnScreen(int v) { return this; }
        public Builder setScrollAreaRatio(float v) { return this; }
        public Builder setScrollGapRatio(float v) { return this; }
        public Builder setLineSpacing(float v) { return this; }
        public Builder setMaxScrollLines(int v) { return this; }
        public Builder setMaxTopLines(int v) { return this; }
        public Builder setMaxBottomLines(int v) { return this; }
        public Builder setShowScroll(boolean v) { return this; }
        public Builder setShowTop(boolean v) { return this; }
        public Builder setShowBottom(boolean v) { return this; }
        public Builder setShowReverse(boolean v) { return this; }
        public Builder setShowPositioned(boolean v) { return this; }
        public Builder setShowSubtitle(boolean v) { return this; }
        public Builder setShowSpecial(boolean v) { return this; }
        public DanmakuConfig build() { return DanmakuConfig.DEFAULT; }
    }
}
