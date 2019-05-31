
package com.android.systemui;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

public class LauncherWatcher {
    public interface Callbacks {
        public void onHomeVisibilityChanged(boolean isVisible);
    }

    private static final String TAG = "LauncherWatcher";

    // KEEP "topAppWindowChanged" from bombing the UI thread
    private static final long TASK_DELAY = 150;

    private Context mContext;
    private PackageManager mPm;
    private ActivityManager mAm;
    private Handler mHandler;
    private final List<Callbacks> mCallbacks = new ArrayList<>();
    private boolean mHomeShowing;

    private CommandQueue.Callbacks mCommandQueueCallbacks = new CommandQueue.Callbacks() {
        @Override
        public void topAppWindowChanged(boolean showMenu) {
            postTaskChecker();
        }
    };

    private KeyguardMonitor.Callback mKeyguardCallback = new KeyguardMonitor.Callback() {
        @Override
        public void onKeyguardShowingChanged() {
            mHomeShowing = false;
            postTaskChecker();
        }
    };

    private Runnable mTaskChecker = new Runnable() {
        @Override
        public void run() {
            ComponentName topActivity = getTopActivity();
            ComponentName currentHome = getCurrentHome();
            if (topActivity != null && currentHome != null) {
                Log.e(TAG, "topAppWindowChanged: topActivity = " + topActivity.flattenToString()
                        + " currentHome = " + currentHome.flattenToString());
                final boolean homeShowingNow = mHomeShowing;
                mHomeShowing = topActivity.equals(currentHome);
                if (mHomeShowing != homeShowingNow) {
                    Log.e(TAG, " homeShowingNow changed: = " + mHomeShowing);
                    dispatchCallbacks(mHomeShowing);
                }
            }
        }
    };

    public LauncherWatcher(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mHandler = Dependency.get(Dependency.MAIN_HANDLER);
        SysUiServiceProvider.getComponent(context, CommandQueue.class)
                .addCallbacks(mCommandQueueCallbacks);
        Dependency.get(KeyguardMonitor.class).addCallback(mKeyguardCallback);
    }

    public void addCallbacks(LauncherWatcher.Callbacks callback) {
        mCallbacks.add(callback);
    }

    public void removeCallbacks(LauncherWatcher.Callbacks callback) {
        mCallbacks.remove(callback);
    }

    public boolean isHomeShowing() {
        return mHomeShowing;
    }

    private void dispatchCallbacks(boolean isHomeShowing) {
        mCallbacks.stream().forEach(o -> ((Callbacks) o).onHomeVisibilityChanged(isHomeShowing));
    }

    private void postTaskChecker() {
        mHandler.removeCallbacks(mTaskChecker);
        mHandler.postDelayed(mTaskChecker, TASK_DELAY);
    }

    private ComponentName getTopActivity() {
        final List<ActivityManager.RunningTaskInfo> tasks = mAm.getRunningTasks(1);
        if (tasks.size() > 0) {
            ActivityManager.RunningTaskInfo info = tasks.get(0);
            return info.topActivity;
        } else {
            return null;
        }
    }

    private ComponentName getCurrentHome() {
        final ArrayList<ResolveInfo> homeActivities = new ArrayList<>();
        return mPm.getHomeActivities(homeActivities);
    }

}
