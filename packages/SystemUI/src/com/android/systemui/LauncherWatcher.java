/**
 * Copyright (C) 2019 The AquariOS Project
 *
 * @author: Randall Rushing <randall.rushing@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Control class that hooks into top app window changes as a means to check
 * if Home is showing. Until we can further optimize this code, do work on a
 * background looper
 *
 */

package com.android.systemui;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

public class LauncherWatcher implements CommandQueue.Callbacks, KeyguardMonitor.Callback {
    public interface Callbacks {
        public void onHomeVisibilityChanged(boolean isVisible);
    }

    private static final String TAG = "LauncherWatcher";

    // Keep "topAppWindowChanged" from bombing the worker thread
    private static final long TASK_DELAY = 100;

    // tell background handler to check top task
    private static final int MSG_POST_TASK_CHECKER = 23;

    // tell main handler to dispatch callbacks
    private static final int MSG_POST_NOTIFY_CALLBACKS = 37;

    private Context mContext;
    private PackageManager mPm;
    private ActivityManager mAm;
    private MainHandler mHandler;
    private BackgroundHandler mBgHandler;
    private Object mBgLock = new Object();
    private final List<Callbacks> mCallbacks = new ArrayList<>();
    private boolean mHomeShowing;

    private class BackgroundHandler extends Handler {

        public BackgroundHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POST_TASK_CHECKER: {
                    ComponentName topActivity = getTopActivity();
                    ComponentName currentHome = getCurrentHome();
                    if (topActivity != null && currentHome != null) {
                        final boolean homeShowingNow = mHomeShowing;
                        synchronized (mBgLock) {
                            mHomeShowing = topActivity.equals(currentHome);
                        }
                        if (mHomeShowing != homeShowingNow) {
                            mHandler.obtainMessage(MSG_POST_NOTIFY_CALLBACKS).sendToTarget();
                        }
                    }
                    break;
                }
            }
        }
    }

    private class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POST_NOTIFY_CALLBACKS: {
                    mCallbacks.stream()
                            .forEach(o -> ((Callbacks) o).onHomeVisibilityChanged(mHomeShowing));
                    break;
                }
            }
        }
    }

    public LauncherWatcher(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mHandler = new MainHandler(Looper.getMainLooper());
        mBgHandler = new BackgroundHandler(Dependency.get(Dependency.BG_LOOPER));
    }

    private void postTaskChecker() {
        mBgHandler.removeMessages(MSG_POST_TASK_CHECKER);
        mBgHandler.sendMessageDelayed(mBgHandler.obtainMessage(MSG_POST_TASK_CHECKER), TASK_DELAY);
    }

    private void startListening() {
        SysUiServiceProvider.getComponent(mContext, CommandQueue.class)
                .addCallbacks(this);
        Dependency.get(KeyguardMonitor.class).addCallback(this);
    }

    private void stopListening() {
        SysUiServiceProvider.getComponent(mContext, CommandQueue.class).removeCallbacks(this);
        Dependency.get(KeyguardMonitor.class).removeCallback(this);
    }

    public ComponentName getTopActivity() {
        final List<ActivityManager.RunningTaskInfo> tasks = mAm.getRunningTasks(1);
        if (tasks.size() > 0) {
            ActivityManager.RunningTaskInfo info = tasks.get(0);
            return info.topActivity;
        } else {
            return null;
        }
    }

    public ComponentName getCurrentHome() {
        final ArrayList<ResolveInfo> homeActivities = new ArrayList<>();
        return mPm.getHomeActivities(homeActivities);
    }

    // start listening when we get our first registered callback
    public void addCallbacks(LauncherWatcher.Callbacks callback) {
        final boolean wasEmpty = mCallbacks.isEmpty();
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
        }
        final boolean isStillEmpty = mCallbacks.isEmpty();
        if (wasEmpty && !isStillEmpty) {
            startListening();
        }
    }

    public void removeCallbacks(LauncherWatcher.Callbacks callback) {
        if (mCallbacks.contains(callback)) {
            mCallbacks.remove(callback);
        }
        if (mCallbacks.isEmpty()) {
            stopListening();
        }
    }

    @Override
    public void topAppWindowChanged(boolean showMenu) {
        postTaskChecker();
    }

    @Override
    public void onKeyguardShowingChanged() {
        mHomeShowing = false;
        postTaskChecker();
    }
}
