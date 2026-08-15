package com.xlz.android.tv.impl;

public interface Diffable<T> {

    boolean isSameItem(T other);

    boolean isSameContent(T other);
}
