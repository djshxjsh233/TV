package com.xlz.android.tv.event;

import com.xlz.android.tv.bean.Config;
import com.xlz.android.tv.bean.Device;
import com.xlz.android.tv.bean.History;

import org.greenrobot.eventbus.EventBus;

public record CastEvent(Config config, Device device, History history) {

    public static void post(Config config, Device device, History history) {
        EventBus.getDefault().post(new CastEvent(config, device, history));
    }
}
