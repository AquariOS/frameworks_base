/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.UserHandle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.Dumpable;
import com.android.systemui.SysUiServiceProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
public class NotificationMediaManager implements Dumpable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

    private static final String NOWPLAYING_SERVICE = "com.google.intelligence.sense";

    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;

    private final StatusBar mStatusBar;

    protected NotificationPresenter mPresenter;
    protected NotificationEntryManager mEntryManager;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;
    private List<MediaUpdateListener> mListeners = new ArrayList<>();

    private String mNowPlayingNotificationKey;

    private Set<String> mBlacklist = new HashSet<String>();

    public interface MediaUpdateListener {
        public default void onMediaUpdated(boolean playing) {}
        public default void setPulseColors(boolean isColorizedMedia, int[] colors) {}
    }

    private final MediaController.Callback mMediaListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onPlaybackStateChanged: " + state);
            }
            if (state != null) {
                if (!isPlaybackActive(state.getState())) {
                    clearCurrentMediaNotification();
                    mPresenter.updateMediaMetaData(true, true);
                }
                if (mStatusBar != null) {
                    mStatusBar.getVisualizer().setPlaying(state.getState()
                            == PlaybackState.STATE_PLAYING);
                }
                setMediaPlaying();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onMetadataChanged: " + metadata);
            }
            mMediaMetadata = metadata;
            mPresenter.updateMediaMetaData(true, true);
            setMediaPlaying();
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            setMediaPlaying();
        }
    };

    public NotificationMediaManager(Context context) {
        mContext = context;
        mMediaSessionManager
                = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        // TODO: use MediaSessionManager.SessionListener to hook us up to future updates
        // in session state

        mStatusBar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationEntryManager entryManager) {
        mPresenter = presenter;
        mEntryManager = entryManager;
    }

    public void onNotificationRemoved(String key) {
        if (key.equals(mMediaNotificationKey)) {
            clearCurrentMediaNotification();
            mPresenter.updateMediaMetaData(true, true);
        }
        if (key.equals(mNowPlayingNotificationKey)) {
            mNowPlayingNotificationKey = null;
            setMediaNotificationText(null, true);
        }
    }

    public String getMediaNotificationKey() {
        return mMediaNotificationKey;
    }

    public MediaController getMediaController() {
        return mMediaController;
    }

    public MediaMetadata getMediaMetadata() {
        return mMediaMetadata;
    }

    public Icon getMediaIcon() {
        if (mMediaNotificationKey == null) return null;

        synchronized (mEntryManager.getNotificationData()) {
            NotificationData.Entry mediaNotification = mEntryManager
                    .getNotificationData().get(mMediaNotificationKey);
            if (mediaNotification == null || mediaNotification.expandedIcon == null) return null;
            return mediaNotification.expandedIcon.getSourceIcon();
        }
    }

    public void findAndUpdateMediaNotifications() {
        boolean metaDataChanged = false;

        synchronized (mEntryManager.getNotificationData()) {
            ArrayList<NotificationData.Entry> activeNotifications = mEntryManager
                    .getNotificationData().getActiveNotifications();
            final int N = activeNotifications.size();

            // Promote the media notification with a controller in 'playing' state, if any.
            NotificationData.Entry mediaNotification = null;
            MediaController controller = null;

            boolean isThereNowPlayingNotification = false;
            for (int i = 0; i < N; i++) {
                final NotificationData.Entry entry = activeNotifications.get(i);
                if (entry.notification.getPackageName().toLowerCase().equals(NOWPLAYING_SERVICE)) {
                    isThereNowPlayingNotification = true;
                    mNowPlayingNotificationKey = entry.notification.getKey();
                    final Notification n = entry.notification.getNotification();
                    String notificationText = null;
                    final String title = n.extras.getString(Notification.EXTRA_TITLE);
                    if (!TextUtils.isEmpty(title)) {
                        notificationText = title;
                    }
                    setMediaNotificationText(notificationText, true);
                    break;
                }
            }
            if (!isThereNowPlayingNotification) {
                setMediaNotificationText(null, true);
            }

            for (int i = 0; i < N; i++) {
                final NotificationData.Entry entry = activeNotifications.get(i);
                if (isMediaNotification(entry)) {
                    final MediaSession.Token token =
                            entry.notification.getNotification().extras.getParcelable(
                                    Notification.EXTRA_MEDIA_SESSION);
                    if (token != null) {
                        MediaController aController = new MediaController(mContext, token);
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            if (DEBUG_MEDIA) {
                                Log.v(TAG, "DEBUG_MEDIA: found mediastyle controller matching "
                                        + entry.notification.getKey());
                            }
                            mediaNotification = entry;
                            controller = aController;
                            break;
                        }
                    }
                }
            }
            if (mediaNotification == null) {
                // Still nothing? OK, let's just look for live media sessions and see if they match
                // one of our notifications. This will catch apps that aren't (yet!) using media
                // notifications.

                if (mMediaSessionManager != null) {
                    // TODO: Should this really be for all users?
                    final List<MediaController> sessions
                            = mMediaSessionManager.getActiveSessionsForUser(
                            null,
                            UserHandle.USER_ALL);

                    for (MediaController aController : sessions) {
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            // now to see if we have one like this
                            final String pkg = aController.getPackageName();

                            for (int i = 0; i < N; i++) {
                                final NotificationData.Entry entry = activeNotifications.get(i);
                                if (entry.notification.getPackageName().equals(pkg)) {
                                    if (DEBUG_MEDIA) {
                                        Log.v(TAG, "DEBUG_MEDIA: found controller matching "
                                                + entry.notification.getKey());
                                    }
                                    controller = aController;
                                    mediaNotification = entry;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (controller != null && !sameSessions(mMediaController, controller)) {
                // We have a new media session
                clearCurrentMediaNotificationSession();
                mMediaController = controller;
                mMediaController.registerCallback(mMediaListener);
                mMediaMetadata = mMediaController.getMetadata();
                setMediaPlaying();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: insert listener, found new controller: "
                            + mMediaController + ", receive metadata: " + mMediaMetadata);
                }

                metaDataChanged = true;
            }

            if (mediaNotification != null
                    && !mediaNotification.notification.getKey().equals(mMediaNotificationKey)) {
                mMediaNotificationKey = mediaNotification.notification.getKey();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                            + mMediaNotificationKey);
                }
            }
        }

        if (metaDataChanged) {
            mEntryManager.updateNotifications();
        }
        mPresenter.updateMediaMetaData(metaDataChanged, true);
    }

    public void clearCurrentMediaNotification() {
        mMediaNotificationKey = null;
        setMediaNotificationText(null, false);
        clearCurrentMediaNotificationSession();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("    mMediaSessionManager=");
        pw.println(mMediaSessionManager);
        pw.print("    mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("    mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("    mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();
    }

    public void addCallback(MediaUpdateListener listener) {
        mListeners.add(listener);
    }

    public boolean isPlaybackActive() {
        return isPlaybackActive(getMediaControllerPlaybackState(mMediaController));
    }

    private boolean isPlaybackActive(int state) {
        return state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
                && state != PlaybackState.STATE_NONE;
    }

    private boolean sameSessions(MediaController a, MediaController b) {
        if (a == b) {
            return true;
        }
        if (a == null) {
            return false;
        }
        return a.controlsSameSession(b);
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    protected boolean isMediaNotification(NotificationData.Entry entry) {
        // TODO: confirm that there's a valid media key
        return entry.getExpandedContentView() != null &&
                entry.getExpandedContentView()
                        .findViewById(com.android.internal.R.id.media_actions) != null;
    }

    private void clearCurrentMediaNotificationSession() {
        mMediaMetadata = null;
        if (mMediaController != null) {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Disconnecting from old controller: "
                        + mMediaController.getPackageName());
            }
            mMediaController.unregisterCallback(mMediaListener);
            setMediaPlaying();
        }
        mMediaController = null;
    }

    private void triggerKeyEvents(int key, MediaController controller, final Handler h) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent evDown = new KeyEvent(when, when, KeyEvent.ACTION_DOWN, key, 0);
        final KeyEvent evUp = KeyEvent.changeAction(evDown, KeyEvent.ACTION_UP);
        h.post(new Runnable() {
            @Override
            public void run() {
                controller.dispatchMediaButtonEvent(evDown);
            }
        });
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                controller.dispatchMediaButtonEvent(evUp);
            }
        }, 20);
    }

    public void onSkipTrackEvent(int key, final Handler h) {
        if (mMediaSessionManager != null) {
            final List<MediaController> sessions
                    = mMediaSessionManager.getActiveSessionsForUser(
                    null, UserHandle.USER_ALL);
            for (MediaController aController : sessions) {
                if (PlaybackState.STATE_PLAYING ==
                        getMediaControllerPlaybackState(aController)) {
                    triggerKeyEvents(key, aController, h);
                    break;
                }
            }
        }
    }

    public void setMediaPlaying() {
        if (PlaybackState.STATE_PLAYING ==
                getMediaControllerPlaybackState(mMediaController)
                || PlaybackState.STATE_BUFFERING ==
                getMediaControllerPlaybackState(mMediaController)) {

            ArrayList<NotificationData.Entry> activeNotifications =
                    mEntryManager.getNotificationData().getAllNotifications();
            int N = activeNotifications.size();
            final String pkg = mMediaController.getPackageName();

            boolean dontPulse = false;
            if (!mBlacklist.isEmpty() && mBlacklist.contains(pkg)) {
                // don't play Pulse for this app
                dontPulse = true;
            }

            boolean mediaNotification= false;
            for (int i = 0; i < N; i++) {
                final NotificationData.Entry entry = activeNotifications.get(i);
                if (entry.notification.getPackageName().equals(pkg)) {
                    // NotificationEntryManager onAsyncInflationFinished will get called
                    // when colors and album are loaded for the notification, then we can send
                    // those info to Pulse
                    mEntryManager.setEntryToRefresh(entry, dontPulse);
                    mediaNotification = true;
                    break;
                }
            }
            if (!mediaNotification) {
                // no notification for this mediacontroller thus no artwork or track info,
                // clean up Ambient Music and Pulse albumart color
                mEntryManager.setEntryToRefresh(null, true);
                setMediaNotificationText(null, false);
            }
            if (!dontPulse) {
                updateListenersMediaUpdated(true);
            }
        } else {
            mEntryManager.setEntryToRefresh(null, true);
            setMediaNotificationText(null, false);
            updateListenersMediaUpdated(false);
        }
    }

    public void setMediaNotificationText(String notificationText, boolean nowPlaying) {
        mPresenter.setAmbientMusicInfo(notificationText, nowPlaying);
    }

    private void updateListenersMediaUpdated(boolean isPlaying) {
        for (MediaUpdateListener listener : mListeners) {
            if (listener != null) {
                listener.onMediaUpdated(isPlaying);
            }
        }
    }

    public void setPulseColors(boolean isColorizedMEdia, int[] colors) {
        for (MediaUpdateListener listener : mListeners) {
            listener.setPulseColors(isColorizedMEdia, colors);
        }
    }

    public void setPulseBlacklist(String blacklist) {
        mBlacklist.clear();
        if (blacklist != null) {
            for (String app : blacklist.split("\\|")) {
                mBlacklist.add(app);
            }
        }
    }
}
