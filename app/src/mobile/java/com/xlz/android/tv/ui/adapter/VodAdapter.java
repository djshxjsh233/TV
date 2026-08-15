package com.xlz.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.xlz.android.tv.bean.Style;
import com.xlz.android.tv.bean.Vod;
import com.xlz.android.tv.databinding.AdapterVodListBinding;
import com.xlz.android.tv.databinding.AdapterVodOvalBinding;
import com.xlz.android.tv.databinding.AdapterVodRectBinding;
import com.xlz.android.tv.ui.base.BaseVodHolder;
import com.xlz.android.tv.ui.base.ViewType;
import com.xlz.android.tv.ui.holder.VodListHolder;
import com.xlz.android.tv.ui.holder.VodOvalHolder;
import com.xlz.android.tv.ui.holder.VodRectHolder;

public class VodAdapter extends BaseDiffAdapter<Vod, BaseVodHolder> {

    private final OnClickListener listener;
    private final Style style;
    private final int[] size;

    public VodAdapter(OnClickListener listener, Style style, int[] size) {
        this.listener = listener;
        this.style = style;
        this.size = size;
    }

    public interface OnClickListener {

        void onItemClick(Vod item);

        boolean onLongClick(Vod item);
    }

    public Style getStyle() {
        return style;
    }

    @Override
    public int getItemViewType(int position) {
        return style.getViewType();
    }

    @Override
    public void onBindViewHolder(@NonNull BaseVodHolder holder, int position) {
        holder.initView(getItem(position));
    }

    @Override
    public void onViewRecycled(@NonNull BaseVodHolder holder) {
        holder.unbind();
    }

    @NonNull
    @Override
    public BaseVodHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return switch (viewType) {
            case ViewType.LIST -> new VodListHolder(AdapterVodListBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
            case ViewType.OVAL -> new VodOvalHolder(AdapterVodOvalBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener).size(size);
            default -> new VodRectHolder(AdapterVodRectBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener).size(size);
        };
    }
}
