package com.flipkart.android.proteus.parser.custom;

import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.custom.ProteusListView;

public class ListViewParser<T extends ListView> extends ViewTypeParser<T> {

    @NonNull
    @Override
    public String getType() {
        return "android.widget.ListView";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.view.ViewGroup";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusListView(context);
    }

    @Override
    protected void addAttributeProcessors() {

    }
}
