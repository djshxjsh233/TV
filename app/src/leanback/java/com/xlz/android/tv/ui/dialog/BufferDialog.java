package com.xlz.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.xlz.android.tv.databinding.DialogBufferBinding;
import com.xlz.android.tv.impl.BufferListener;
import com.xlz.android.tv.setting.PlayerSetting;
import com.xlz.android.tv.utils.KeyUtil;
import com.xlz.android.tv.utils.SliderUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class BufferDialog extends BaseAlertDialog {

    private DialogBufferBinding binding;

    public static void show(FragmentActivity activity) {
        new BufferDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogBufferBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        SliderUtil.setValue(binding.slider, PlayerSetting.getBuffer());
    }

    @Override
    protected void initEvent() {
        binding.slider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) ((BufferListener) requireActivity()).setBuffer(Math.round(SliderUtil.snap(slider, value)));
        });
        binding.slider.setOnKeyListener((view, keyCode, event) -> {
            boolean enter = KeyUtil.isEnterKey(event);
            if (enter) dismiss();
            return enter;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }
}
