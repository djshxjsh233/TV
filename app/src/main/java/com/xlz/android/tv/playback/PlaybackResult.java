package com.xlz.android.tv.playback;

import com.xlz.android.tv.bean.Result;

public record PlaybackResult<T>(T request, Result result) {
}
