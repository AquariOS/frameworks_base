/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.view.MotionEvent.ACTION_OUTSIDE;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.navigation.pulse.PulseController;
import com.android.systemui.navigation.pulse.PulseController.PulseHost;
import com.android.systemui.statusbar.policy.DeadZone;

public class NavigationBarFrame extends FrameLayout implements PulseHost {

    private DeadZone mDeadZone = null;
    private boolean mEnabled = false;
    private PulseController mPulse;

    public NavigationBarFrame(@NonNull Context context) {
        this(context, null);
    }

    public NavigationBarFrame(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NavigationBarFrame(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        mPulse = Dependency.get(PulseController.class);
        mPulse.setHost(this);
    }

    public void setDeadZone(@NonNull DeadZone deadZone) {
        mDeadZone = deadZone;
    }

    public void disableDeadZone() {
        mEnabled = false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == ACTION_OUTSIDE) {
            if (mDeadZone != null && mEnabled) {
                return mDeadZone.onTouchEvent(event);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mPulse.onSizeChanged(w, h, oldw, oldh);
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    public void onDraw(Canvas canvas) {
        mPulse.onDraw(canvas);
        super.onDraw(canvas);
    }
}