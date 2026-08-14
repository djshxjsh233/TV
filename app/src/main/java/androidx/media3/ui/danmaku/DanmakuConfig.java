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
    public static final DanmakuConfig DEFAULT = new DanmakuConfig.Builder().build();

    private DanmakuConfig(Builder b) {}

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
        public DanmakuConfig build() { return new DanmakuConfig(this); }
    }
}
