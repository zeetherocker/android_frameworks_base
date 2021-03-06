/*
 * Copyright (C) 2010 The Android Open Source Project
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
 */

package com.android.systemui.statusbar;

import android.annotation.ChaosLab;
import android.annotation.ChaosLab.Classification;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerNative;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.content.res.ThemeConfig;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.util.omni.OmniSwitchConstants;
import com.android.internal.util.slim.DeviceUtils;
import com.android.internal.widget.SizeAdaptiveLayout;
import com.android.systemui.chaos.lab.gestureanywhere.GestureAnywhereView;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.AOKPSearchPanelView;
import com.android.systemui.SystemUI;
import com.android.systemui.aokp.AppWindow;
import com.android.systemui.slimrecent.RecentController;
import com.android.systemui.statusbar.notification.Hover;
import com.android.systemui.statusbar.notification.HoverCling;
import com.android.systemui.statusbar.notification.NotificationHelper;
import com.android.systemui.statusbar.phone.KeyguardTouchDelegate;
import com.android.systemui.statusbar.policy.NotificationRowLayout;
import com.android.systemui.statusbar.policy.activedisplay.ActiveDisplayView;
import com.android.systemui.statusbar.appcirclesidebar.AppCircleSidebar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public abstract class BaseStatusBar extends SystemUI implements
        CommandQueue.Callbacks {
    public static final String TAG = "StatusBar";
    public static final boolean DEBUG = false;
    public static final boolean MULTIUSER_DEBUG = false;

    protected static final int MSG_TOGGLE_RECENTS_PANEL = 1020;
    protected static final int MSG_CLOSE_RECENTS_PANEL = 1021;
    protected static final int MSG_PRELOAD_RECENT_APPS = 1022;
    protected static final int MSG_CANCEL_PRELOAD_RECENT_APPS = 1023;
    protected static final int MSG_OPEN_SEARCH_PANEL = 1024;
    protected static final int MSG_CLOSE_SEARCH_PANEL = 1025;
    protected static final int MSG_SHOW_HEADS_UP = 1026;
    protected static final int MSG_HIDE_HEADS_UP = 1027;
    protected static final int MSG_ESCALATE_HEADS_UP = 1028;
    protected static final int MSG_TOGGLE_SCREENSHOT = 1029;
    protected static final int MSG_TOGGLE_LAST_APP = 1030;
    protected static final int MSG_TOGGLE_KILL_APP = 1031;

    protected static final boolean ENABLE_HEADS_UP = true;
    // scores above this threshold should be displayed in heads up mode.
    protected static final int INTERRUPTION_THRESHOLD = 1;
    // Should match the value in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";

    public static final int EXPANDED_LEAVE_ALONE = -10000;
    public static final int EXPANDED_FULL_OPEN = -10001;

    private static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;
    private static final int COLLAPSE_AFTER_DISMISS_DELAY = 200;
    private static final int COLLAPSE_AFTER_REMOVE_DELAY = 400;

    protected CommandQueue mCommandQueue;
    protected INotificationManager mNotificationManager;
    protected IStatusBarService mBarService;
    protected H mHandler = createHandler();

    // all notifications
    protected NotificationData mNotificationData = new NotificationData();
    protected NotificationRowLayout mPile;

    protected NotificationData.Entry mInterruptingNotificationEntry;
    protected long mInterruptingNotificationTime;

    // used to notify status bar for suppressing notification LED
    protected boolean mPanelSlightlyVisible;

    // Search panel
    protected AOKPSearchPanelView mSearchPanelView;
    protected AppWindow mAppWindow;

    protected PopupMenu mNotificationBlamePopup;

    protected int mCurrentUserId = 0;

    protected int mLayoutDirection = -1; // invalid
    private Locale mLocale;
    protected boolean mUseHeadsUp = false;

    protected IDreamManager mDreamManager;
    PowerManager mPowerManager;
    protected int mRowHeight;

    // Notification helper
    protected NotificationHelper mNotificationHelper;

    protected FrameLayout mStatusBarContainer;

    private Runnable mPanelCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
        }
    };

    // Hover
    protected Hover mHover;
    protected boolean mHoverActive;
    protected boolean mHoverHideButton;
    protected ImageView mHoverButton;
    protected HoverCling mHoverCling;

    // Notification helper
    protected NotificationHelper mNotificationHelper;

    // UI-specific methods

    /**
     * Create all windows necessary for the status bar (including navigation, overlay panels, etc)
     * and add them to the window manager.
     */
    protected abstract void createAndAddWindows();

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;
    protected abstract void refreshLayout(int layoutDirection);

    protected Display mDisplay;

    private boolean mDeviceProvisioned = false;
    private int mAutoCollapseBehaviour;

    private RecentsComponent mRecents;
    private RecentController mSlimRecents;

    private ArrayList<String> mDndList;
    private ArrayList<String> mBlacklist;

    protected int mExpandedDesktopStyle = 0;

    private boolean mShowNotificationCounts;

    protected AppSidebar mAppSidebar;
    protected int mSidebarPosition;

    protected ActiveDisplayView mActiveDisplayView;

    protected AppCircleSidebar mAppCircleSidebar;

    @ChaosLab(name="GestureAnywhere", classification=Classification.NEW_FIELD)
    protected GestureAnywhereView mGestureAnywhereView;

    private boolean mSlimRecentsEnabled;

    public INotificationManager getNotificationManager() {
        return mNotificationManager;
    }

    public IStatusBarService getStatusBarService() {
        return mBarService;
    }

    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    public NotificationData getNotifications() {
        return mNotificationData;
    }

    public RemoteViews.OnClickHandler getNotificationClickHandler() {
        return mOnClickHandler;
    }

    private ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean provisioned = 0 != Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
            if (provisioned != mDeviceProvisioned) {
                mDeviceProvisioned = provisioned;
                updateNotificationIcons();
            }

            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HEADS_UP_CUSTOM_VALUES),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HEADS_UP_BLACKLIST_VALUES),
                    false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_COLLAPSE_ON_DISMISS), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.EXPANDED_DESKTOP_STATE), false, this);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.EXPANDED_DESKTOP_STYLE), false, this);
            mSlimRecentsEnabled = Settings.System.getIntForUser(
                    mContext.getContentResolver(), Settings.System.RECENTS_USE_SLIM,
                    0, UserHandle.USER_CURRENT) == 1;
            update();
        }
    };

        private void update() {
            ContentResolver resolver = mContext.getContentResolver();
            mAutoCollapseBehaviour = Settings.System.getIntForUser(resolver,
                    Settings.System.STATUS_BAR_COLLAPSE_ON_DISMISS,
                    Settings.System.STATUS_BAR_COLLAPSE_IF_NO_CLEARABLE, UserHandle.USER_CURRENT);
            mExpandedDesktopStyle = 0;
            if (Settings.System.getIntForUser(resolver,
                    Settings.System.EXPANDED_DESKTOP_STATE, 0, UserHandle.USER_CURRENT) != 0) {
                mExpandedDesktopStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.EXPANDED_DESKTOP_STYLE, 0, UserHandle.USER_CURRENT);
            final String dndString = Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.HEADS_UP_CUSTOM_VALUES);
            final String blackString = Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.HEADS_UP_BLACKLIST_VALUES);
            splitAndAddToArrayList(mDndList, dndString, "\\|");
            splitAndAddToArrayList(mBlacklist, blackString, "\\|");
       }
    };

    private RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {
        @Override
        public boolean onClickHandler(View view, PendingIntent pendingIntent, Intent fillInIntent) {
            if (DEBUG) {
                Log.v(TAG, "Notification click handler invoked for intent: " + pendingIntent);
            }

            // User is clicking a button inside the notification, stop countdowns and
            // restart override one depending on notification expansion.
            // We just ignore incoming call case cause is handled differently, as soon as dialer shows, is gone.
            mHover.processOverridingQueue(mHover.isExpanded());

            final boolean isActivity = pendingIntent.isActivity();
            if (isActivity) {
                try {
                    // The intent we are sending is for the application, which
                    // won't have permission to immediately start an activity after
                    // the user switches to home.  We know it is safe to do at this
                    // point, so make sure new activity switches are now allowed.
                    ActivityManagerNative.getDefault().resumeAppSwitches();
                    // Also, notifications can be launched from the lock screen,
                    // so dismiss the lock screen when the activity starts.
                    ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                } catch (RemoteException e) {
                }
            }

            boolean handled = super.onClickHandler(view, pendingIntent, fillInIntent);

            if (isActivity && handled) {
                // close the shade if it was open
                animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                visibilityChanged(false);
            }
            return handled;
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (true) Log.v(TAG, "userId " + mCurrentUserId + " is in the house");
                userSwitched(mCurrentUserId);
            }
        }
    };

    public void start() {
        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDisplay = mWindowManager.getDefaultDisplay();

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));

        mSettingsObserver.onChange(false); // set up

        mDndList = new ArrayList<String>();
        mBlacklist = new ArrayList<String>();

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), true,
                mSettingsObserver);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.RECENTS_USE_SLIM), true,
                mSettingsObserver, UserHandle.USER_ALL);

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mRecents = getComponent(RecentsComponent.class);

        mLocale = mContext.getResources().getConfiguration().locale;
        mLayoutDirection = TextUtils.getLayoutDirectionFromLocale(mLocale);

        mHover = new Hover(this, mContext);
        mHoverCling = new HoverCling(mContext);
        mNotificationHelper = new NotificationHelper(this, mContext);

        mHover.setNotificationHelper(mNotificationHelper);

        if (mSlimRecentsEnabled) {
            mSlimRecents = new RecentController(mContext, mLayoutDirection);
        } else {
            mRecents = getComponent(RecentsComponent.class);
        }

        mShowNotificationCounts = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_NOTIF_COUNT, 0) == 1;

        mNotificationHelper = new NotificationHelper(this, mContext);

        mStatusBarContainer = new FrameLayout(mContext);

        // Connect in to the status bar manager service
        StatusBarIconList iconList = new StatusBarIconList();
        ArrayList<IBinder> notificationKeys = new ArrayList<IBinder>();
        ArrayList<StatusBarNotification> notifications = new ArrayList<StatusBarNotification>();
        mCommandQueue = new CommandQueue(this, iconList);

        int[] switches = new int[7];
        ArrayList<IBinder> binders = new ArrayList<IBinder>();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconList, notificationKeys, notifications,
                    switches, binders);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        createAndAddWindows();

        disable(switches[0]);
        setSystemUiVisibility(switches[1], 0xffffffff);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4]);
        setHardKeyboardStatus(switches[5] != 0, switches[6] != 0);

        // Set up the initial icon state
        int N = iconList.size();
        int viewIndex = 0;
        for (int i=0; i<N; i++) {
            StatusBarIcon icon = iconList.getIcon(i);
            if (icon != null) {
                addIcon(iconList.getSlot(i), i, viewIndex, icon);
                viewIndex++;
            }
        }

        // Set up the initial notification state
        N = notificationKeys.size();
        if (N == notifications.size()) {
            for (int i=0; i<N; i++) {
                addNotification(notificationKeys.get(i), notifications.get(i));
            }
        } else {
            Log.wtf(TAG, "Notification list length mismatch: keys=" + N
                    + " notifications=" + notifications.size());
        }

        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x menu=0x%08x imeButton=0x%08x",
                   iconList.size(),
                   switches[0],
                   switches[1],
                   switches[2],
                   switches[3]
                   ));
        }
        mAppWindow = new AppWindow(mContext);
        mCurrentUserId = ActivityManager.getCurrentUser();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mBroadcastReceiver, filter);
        SidebarObserver observer = new SidebarObserver(mHandler);
        observer.observe();

        // Listen for HOVER state
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.HOVER_ACTIVE),
                        false, new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateHoverActive();
            }
        });

        // Listen for HOVER button override
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.HOVER_HIDE_BUTTON),
                        false, new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateHoverActive();
            }
        });

        updateHoverActive();

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.DIALPAD_STATE),
                        false, new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                boolean showing = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.DIALPAD_STATE, 0) != 0;
                if(showing) mHover.dismissHover(false, false);
            }
        });
    }

    public Hover getHoverInstance() {
        if(mHover == null) mHover = new Hover(this, mContext);
        return mHover;
    }

    protected void updateHoverButton(boolean shouldBeVisible) {
        mHoverButton.setVisibility((shouldBeVisible && !mHoverHideButton) ? View.VISIBLE : View.GONE);
    }

    protected void updateHoverButton() {
            updateHoverButton(true);
    }

    public void updateHoverActive() {
            mHoverActive = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HOVER_ACTIVE, 0) == 1;

            mHoverHideButton = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HOVER_HIDE_BUTTON, 0) == 1;

            updateHoverButton();
            if (!mHoverHideButton) {
                mHoverButton.setImageResource(mHoverActive ?
                    R.drawable.ic_notify_hover_pressed : R.drawable.ic_notify_hover_normal);
            }

            mHover.setHoverActive(mHoverActive);
    }

    public void userSwitched(int newUserId) {
        // should be overridden
    }

    public boolean notificationIsForCurrentUser(StatusBarNotification n) {
        final int thisUserId = mCurrentUserId;
        final int notificationUserId = n.getUserId();
        if (DEBUG && MULTIUSER_DEBUG) {
            Log.v(TAG, String.format("%s: current userid: %d, notification userid: %d",
                    n, thisUserId, notificationUserId));
        }
        return notificationUserId == UserHandle.USER_ALL
                || thisUserId == notificationUserId;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        final Locale locale = mContext.getResources().getConfiguration().locale;
        final int ld = TextUtils.getLayoutDirectionFromLocale(locale);
        if (! locale.equals(mLocale) || ld != mLayoutDirection) {
            if (DEBUG) {
                Log.v(TAG, String.format(
                        "config changed locale/LD: %s (%d) -> %s (%d)", mLocale, mLayoutDirection,
                        locale, ld));
            }
            mLocale = locale;
            mLayoutDirection = ld;
            refreshLayout(ld);
        }
    }

    protected View updateNotificationVetoButton(View row, StatusBarNotification n) {
        View vetoButton = row.findViewById(R.id.veto);
        if (n.isClearable() || (mInterruptingNotificationEntry != null
                && mInterruptingNotificationEntry.row == row)) {
            final String _pkg = n.getPackageName();
            final String _tag = n.getTag();
            final int _id = n.getId();
            vetoButton.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        // Accessibility feedback
                        v.announceForAccessibility(
                                mContext.getString(R.string.accessibility_notification_dismissed));
                        try {
                            mBarService.onNotificationClear(_pkg, _tag, _id);

                        } catch (RemoteException ex) {
                            // system process is dead if we're here.
                        }
                    }
                });
            vetoButton.setVisibility(View.VISIBLE);
        } else {
            vetoButton.setVisibility(View.GONE);
        }
        vetoButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return vetoButton;
    }


    protected void applyLegacyRowBackground(StatusBarNotification sbn, View content) {
        if (sbn.getNotification().contentView.getLayoutId() !=
                com.android.internal.R.layout.notification_template_base) {
            int version = 0;
            try {
                ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(sbn.getPackageName(), 0);
                version = info.targetSdkVersion;
            } catch (NameNotFoundException ex) {
                Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
            }
            if (version > 0 && version < Build.VERSION_CODES.GINGERBREAD) {
                content.setBackgroundResource(R.drawable.notification_row_legacy_bg);
            } else {
                content.setBackgroundResource(com.android.internal.R.drawable.notification_bg);
            }
        }
    }

    private void startApplicationDetailsActivity(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null));
        intent.setComponent(intent.resolveActivity(mContext.getPackageManager()));
        TaskStackBuilder.create(mContext).addNextIntentWithParentStack(intent).startActivities(
                null, UserHandle.CURRENT);
    }

    private void launchFloating(PendingIntent pIntent) {
        Intent overlay = new Intent();
        overlay.addFlags(Intent.FLAG_FLOATING_WINDOW | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            ActivityManagerNative.getDefault().resumeAppSwitches();
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        try {
            pIntent.send(mContext, 0, overlay);
        } catch (PendingIntent.CanceledException e) {
            // the stack trace isn't very helpful here.  Just log the exception message.
            Slog.w(TAG, "Sending contentIntent failed: " + e);
        }
    }

    protected View.OnLongClickListener getNotificationLongClicker() {
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                NotificationData.Entry  entry = (NotificationData.Entry) v.getTag();
                StatusBarNotification sbn = entry.notification;

                final String packageNameF = sbn.getPackageName();
                final PendingIntent contentIntent = sbn.getNotification().contentIntent;

                if (packageNameF == null) return false;
                if (v.getWindowToken() == null) return false;

                mNotificationBlamePopup = new PopupMenu(mContext, v);
                mNotificationBlamePopup.getMenuInflater().inflate(
                        R.menu.notification_popup_menu,
                        mNotificationBlamePopup.getMenu());
                final ContentResolver cr = mContext.getContentResolver();
                if (Settings.Secure.getInt(cr,
                        Settings.Secure.DEVELOPMENT_SHORTCUT, 0) == 0) {
                    mNotificationBlamePopup.getMenu()
                            .findItem(R.id.notification_inspect_item_force_stop).setVisible(false);
                    mNotificationBlamePopup.getMenu()
                            .findItem(R.id.notification_inspect_item_wipe_app).setVisible(false);
                } else {
                    try {
                        PackageManager pm = (PackageManager) mContext.getPackageManager();
                        ApplicationInfo mAppInfo = pm.getApplicationInfo(packageNameF, 0);
                        DevicePolicyManager mDpm = (DevicePolicyManager) mContext.
                                getSystemService(Context.DEVICE_POLICY_SERVICE);
                        if ((mAppInfo.flags&(ApplicationInfo.FLAG_SYSTEM
                              | ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA))
                              == ApplicationInfo.FLAG_SYSTEM
                              || mDpm.packageHasActiveAdmins(packageNameF)) {
                            mNotificationBlamePopup.getMenu()
                            .findItem(R.id.notification_inspect_item_wipe_app).setEnabled(false);
                        }
                    } catch (NameNotFoundException ex) {
                        Slog.e(TAG, "Failed looking up ApplicationInfo for " + packageNameF, ex);
                    }
                }

                mNotificationBlamePopup
                .setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == R.id.notification_inspect_item) {
                            startApplicationDetailsActivity(packageNameF);
                            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                        } else if (item.getItemId() == R.id.notification_inspect_item_force_stop) {
                            ActivityManager am = (ActivityManager) mContext
                                    .getSystemService(
                                    Context.ACTIVITY_SERVICE);
                            am.forceStopPackage(packageNameF);
                        } else if (item.getItemId() == R.id.notification_inspect_item_wipe_app) {
                            ActivityManager am = (ActivityManager) mContext
                                    .getSystemService(Context.ACTIVITY_SERVICE);
                            am.clearApplicationUserData(packageNameF,
                                    new FakeClearUserDataObserver());
                        } else if (item.getItemId() == R.id.notification_floating_item) {
                            boolean allowed = true;
                            try {
                                // preloaded apps are added to the blacklist array when is recreated, handled in the notification manager
                                allowed = mNotificationManager.isPackageAllowedForFloatingMode(packageNameF);
                            } catch (android.os.RemoteException ex) {
                                // System is dead
                            }
                            if (allowed) {
                                launchFloating(contentIntent);
                                animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                            } else {
                                String text = mContext.getResources().getString(R.string.floating_mode_blacklisted_app);
                                int duration = Toast.LENGTH_LONG;
                                animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
                                Toast.makeText(mContext, text, duration).show();
                            }
                        } else {
                            return false;
                        }
                        return true;
                    }
                });

                mNotificationBlamePopup.setOnDismissListener(new PopupMenu.OnDismissListener() {
                    @Override
                    public void onDismiss(PopupMenu popupMenu) {
                        mNotificationBlamePopup = null;
                    }
                });

                mNotificationBlamePopup.show();

                return true;
            }
        };
    }

    class FakeClearUserDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(final String packageName, final boolean succeeded) {
        }
    }

    public void dismissPopups() {
        if (mNotificationBlamePopup != null) {
            mNotificationBlamePopup.dismiss();
            mNotificationBlamePopup = null;
        }
    }

    public void onHeadsUpDismissed() {
    }

    @Override
    public void toggleRecentApps() {
        int msg = MSG_TOGGLE_RECENTS_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void preloadRecentApps() {
        int msg = MSG_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void cancelPreloadRecentApps() {
        int msg = MSG_CANCEL_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void showSearchPanel() {
        int msg = MSG_OPEN_SEARCH_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void hideSearchPanel() {
        int msg = MSG_CLOSE_SEARCH_PANEL;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void setButtonDrawable(int buttonId, int iconId) {}

    @Override
    public void toggleScreenshot() {
        int msg = MSG_TOGGLE_SCREENSHOT;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void toggleLastApp() {
        int msg = MSG_TOGGLE_LAST_APP;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void toggleKillApp() {
        int msg = MSG_TOGGLE_KILL_APP;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    protected abstract WindowManager.LayoutParams getSearchLayoutParams(
            LayoutParams layoutParams);

    protected void updateSearchPanel() {
        // Search Panel
        boolean visible = false;
        if (mSearchPanelView != null) {
            visible = mSearchPanelView.isShowing();
            mWindowManager.removeView(mSearchPanelView);
        }

        // Provide SearchPanel with a temporary parent to allow layout params to work.
        LinearLayout tmpRoot = new LinearLayout(mContext);
        mSearchPanelView = (AOKPSearchPanelView) LayoutInflater.from(mContext).inflate(
                 R.layout.status_bar_search_panel, tmpRoot, false);
        mSearchPanelView.setOnTouchListener(
                 new TouchOutsideListener(MSG_CLOSE_SEARCH_PANEL, mSearchPanelView));
        mSearchPanelView.setVisibility(View.GONE);

        WindowManager.LayoutParams lp = getSearchLayoutParams(mSearchPanelView.getLayoutParams());

        mWindowManager.addView(mSearchPanelView, lp);
        mSearchPanelView.setBar(this);
        if (visible) {
            mSearchPanelView.show(true, false);
        }
    }

    protected H createHandler() {
         return new H();
    }

    static void sendCloseSystemWindows(Context context, String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    protected abstract View getStatusBarView();

    protected View.OnTouchListener mRecentsPreloadOnTouchListener = new View.OnTouchListener() {
        // additional optimization when we have software system buttons - start loading the recent
        // tasks on touch down
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getAction() & MotionEvent.ACTION_MASK;
            if (action == MotionEvent.ACTION_DOWN) {
                preloadRecentTasksList();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                cancelPreloadingRecentTasksList();
            } else if (action == MotionEvent.ACTION_UP) {
                if (!v.isPressed()) {
                    cancelPreloadingRecentTasksList();
                }

            }
            return false;
        }
    };

    private boolean isOmniSwitchEnabled() {
        int settingsValue = Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.RECENTS_USE_OMNISWITCH, 0);
        return (settingsValue == 1);
    }

    protected void toggleRecentsActivity() {
        if (isOmniSwitchEnabled()){
            Intent showIntent = new Intent(OmniSwitchConstants.ACTION_TOGGLE_OVERLAY);
            mContext.sendBroadcastAsUser(showIntent, UserHandle.CURRENT);
        } else {
            if (mRecents != null || mSlimRecents != null) {
                if (mSlimRecentsEnabled) {
                    mSlimRecents.toggleRecents(mDisplay, mLayoutDirection, getStatusBarView());
                } else {
                    mRecents.toggleRecents(mDisplay, mLayoutDirection, getStatusBarView(),
                            mExpandedDesktopStyle);
                }
            }
        }
    }

    protected void preloadRecentTasksList() {
        if (!isOmniSwitchEnabled()) {
            if (mRecents != null || mSlimRecents != null) {
                if (mSlimRecentsEnabled) {
                    mSlimRecents.preloadRecentTasksList();
                } else {
                    mRecents.preloadRecentTasksList();
                }
            }
        }
    }

    protected void cancelPreloadingRecentTasksList() {
        if (!isOmniSwitchEnabled()) {
            if (mRecents != null || mSlimRecents != null) {
                if (mSlimRecentsEnabled) {
                    mSlimRecents.cancelPreloadingRecentTasksList();
                } else {
                    mRecents.cancelPreloadingRecentTasksList();
                }
            }
        }
    }

    protected void closeRecents() {
        if (isOmniSwitchEnabled()) {
            Intent hideIntent = new Intent(OmniSwitchConstants.ACTION_HIDE_OVERLAY);
            mContext.sendBroadcastAsUser(hideIntent, UserHandle.CURRENT);
        } else {
            if (mRecents != null || mSlimRecents != null) {
                if (mSlimRecentsEnabled) {
                    mSlimRecents.closeRecents();
                } else {
                    mRecents.closeRecents();
                }
            }
        }
    }

    protected void rebuildRecentsScreen() {
        if (mSlimRecents != null && mSlimRecentsEnabled) {
            mSlimRecents.rebuildRecentsScreen();
        }
    }

    public abstract void resetHeadsUpDecayTimer();
    public abstract void hideHeadsUp();

    protected class H extends Handler {
        public void handleMessage(Message m) {
            Intent intent;
            switch (m.what) {
             case MSG_TOGGLE_RECENTS_PANEL:
                 toggleRecentsActivity();
                 break;
             case MSG_CLOSE_RECENTS_PANEL:
                 closeRecents();
                 break;
             case MSG_PRELOAD_RECENT_APPS:
                  preloadRecentTasksList();
                  break;
             case MSG_CANCEL_PRELOAD_RECENT_APPS:
                  cancelPreloadingRecentTasksList();
                  break;
             case MSG_OPEN_SEARCH_PANEL:
                 if (DEBUG) Log.d(TAG, "opening search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isAssistantAvailable()) {
                     mSearchPanelView.show(true, true);
                     onShowSearchPanel();
                 }
                 break;
             case MSG_CLOSE_SEARCH_PANEL:
                 if (DEBUG) Log.d(TAG, "closing search panel");
                 if (mSearchPanelView != null && mSearchPanelView.isShowing()) {
                     mSearchPanelView.show(false, true);
                     onHideSearchPanel();
                 }
                 break;
             case MSG_TOGGLE_SCREENSHOT:
                 if (DEBUG) Slog.d(TAG, "toggle screenshot");
                 takeScreenshot();
                 break;
             case MSG_TOGGLE_LAST_APP:
                 if (DEBUG) Slog.d(TAG, "toggle last app");
                 getLastApp();
                 break;
             case MSG_TOGGLE_KILL_APP:
                 if (DEBUG) Slog.d(TAG, "toggle kill app");
                 mHandler.post(mKillTask);
                 break;
            }
        }
    }

    public class TouchOutsideListener implements View.OnTouchListener {
        private int mMsg;
        private StatusBarPanel mPanel;

        public TouchOutsideListener(int msg, StatusBarPanel panel) {
            mMsg = msg;
            mPanel = panel;
        }

        public boolean onTouch(View v, MotionEvent ev) {
            final int action = ev.getAction();
            if (action == MotionEvent.ACTION_OUTSIDE
                || (action == MotionEvent.ACTION_DOWN
                    && !mPanel.isInContentArea((int)ev.getX(), (int)ev.getY()))) {
                mHandler.removeMessages(mMsg);
                mHandler.sendEmptyMessage(mMsg);
                return true;
            }
            return false;
        }
    }

    protected void workAroundBadLayerDrawableOpacity(View v) {
    }

    protected void onHideSearchPanel() {
    }

    protected void onShowSearchPanel() {
    }

    public boolean inflateViews(NotificationData.Entry entry, ViewGroup parent) {
        int minHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.default_notification_min_height);
        int maxHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.default_notification_max_height);
        StatusBarNotification sbn = entry.notification;
        RemoteViews contentView = sbn.getNotification().contentView;
        RemoteViews bigContentView = sbn.getNotification().bigContentView;
        if (contentView == null) {
            return false;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        ExpandableNotificationRow row = (ExpandableNotificationRow) inflater.inflate(
                R.layout.status_bar_notification_row, parent, false);

        // for blaming (see SwipeHelper.setLongPressListener)
        row.setTag(entry);

        workAroundBadLayerDrawableOpacity(row);
        View vetoButton = updateNotificationVetoButton(row, sbn);
        vetoButton.setContentDescription(mContext.getString(
                R.string.accessibility_remove_notification));

        // NB: the large icon is now handled entirely by the template

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(R.id.content);
        ViewGroup adaptive = (ViewGroup)row.findViewById(R.id.adaptive);

        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        PendingIntent contentIntent = sbn.getNotification().contentIntent;
        if (contentIntent != null) {
            final View.OnClickListener listener = new NotificationClicker(contentIntent,
                    sbn.getPackageName(), sbn.getTag(), sbn.getId());
            content.setOnClickListener(listener);
        } else {
            content.setOnClickListener(null);
        }

        View contentViewLocal = null;
        View bigContentViewLocal = null;
        final ThemeConfig themeConfig = mContext.getResources().getConfiguration().themeConfig;
        String themePackageName = themeConfig != null ?
                themeConfig.getOverlayPkgNameForApp(mContext.getPackageName()) : null;
        try {
            contentViewLocal = contentView.apply(mContext, adaptive, mOnClickHandler,
                    themePackageName);
            if (bigContentView != null) {
                bigContentViewLocal = bigContentView.apply(mContext, adaptive, mOnClickHandler,
                        themePackageName);
            }
        }
        catch (RuntimeException e) {
            final String ident = sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId());
            Log.e(TAG, "couldn't inflate view for notification " + ident, e);
            return false;
        }

        if (contentViewLocal != null) {
            SizeAdaptiveLayout.LayoutParams params =
                    new SizeAdaptiveLayout.LayoutParams(contentViewLocal.getLayoutParams());
            params.minHeight = minHeight;
            params.maxHeight = minHeight;
            adaptive.addView(contentViewLocal, params);
        }
        if (bigContentViewLocal != null) {
            SizeAdaptiveLayout.LayoutParams params =
                    new SizeAdaptiveLayout.LayoutParams(bigContentViewLocal.getLayoutParams());
            params.minHeight = minHeight+1;
            params.maxHeight = maxHeight;
            adaptive.addView(bigContentViewLocal, params);
        }
        row.setDrawingCacheEnabled(true);

        applyLegacyRowBackground(sbn, content);

        if (MULTIUSER_DEBUG) {
            TextView debug = (TextView) row.findViewById(R.id.debug_info);
            if (debug != null) {
                debug.setVisibility(View.VISIBLE);
                debug.setText("U " + entry.notification.getUserId());
            }
        }
        entry.row = row;
        entry.row.setRowHeight(mRowHeight);
        entry.content = content;
        entry.expanded = contentViewLocal;
        entry.setBigContentView(bigContentViewLocal);

        return true;
    }

    public NotificationClicker makeClicker(PendingIntent intent, String pkg, String tag, int id) {
        return new NotificationClicker(intent, pkg, tag, id);
    }

    public NotificationClicker makeClicker(Intent intent) {
        return new NotificationClicker(intent);
    }

    public class NotificationClicker implements View.OnClickListener {
        private KeyguardTouchDelegate mKeyguard;
        private PendingIntent mPendingIntent;
        private Intent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;
        private boolean mFloat;

    public class NotificationClicker implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;
        public boolean mFloat;

        public NotificationClicker(PendingIntent intent, String pkg, String tag, int id) {
            this();
            mPendingIntent = intent;
            mPkg = pkg;
            mTag = tag;
            mId = id;
        }

        public NotificationClicker(Intent intent) {
            this();
            mIntent = intent;
        }

        public NotificationClicker() {
            mKeyguard = KeyguardTouchDelegate.getInstance(mContext);
        }

        public void makeFloating(boolean floating) {
            mFloat = floating;
        }

        public void onClick(View v) {
            if (mNotificationBlamePopup != null) return;
            try {
                // The intent we are sending is for the application, which
                // won't have permission to immediately start an activity after
                // the user switches to home.  We know it is safe to do at this
                // point, so make sure new activity switches are now allowed.
                ActivityManagerNative.getDefault().resumeAppSwitches();
                // Also, notifications can be launched from the lock screen,
                // so dismiss the lock screen when the activity starts.
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
            }

            int flags = Intent.FLAG_FLOATING_WINDOW | Intent.FLAG_ACTIVITY_CLEAR_TASK;
            boolean allowed = true; // default on, except for preloaded false
            try {
                // preloaded apps are added to the blacklist array when is recreated, handled in the notification manager
                allowed = mNotificationManager.isPackageAllowedForFloatingMode(mPkg);
            } catch (android.os.RemoteException ex) {
                // System is dead
            }
            if (mPendingIntent != null) {
                int[] pos = new int[2];
                v.getLocationOnScreen(pos);
                Intent overlay = new Intent();
                if (mFloat && allowed) overlay.addFlags(flags);
                overlay.setSourceBounds(
                        new Rect(pos[0], pos[1], pos[0] + v.getWidth(), pos[1] + v.getHeight()));
                try {
                    mPendingIntent.send(mContext, 0, overlay);
                } catch (PendingIntent.CanceledException e) {
                    // the stack trace isn't very helpful here.  Just log the exception message.
                    Log.w(TAG, "Sending contentIntent failed: " + e);
                }
            } else if(mIntent != null) {
                if (mFloat && allowed) mIntent.addFlags(flags);
                mContext.startActivity(mIntent);
            }

            if(mKeyguard.isShowingAndNotHidden()) mKeyguard.dismiss();

            if(mPkg != null) { // check if we're dealing with a notification
                try {
                    mBarService.onNotificationClick(mPkg, mTag, mId);
                } catch (RemoteException ex) {
                    // system process is dead if we're here.
                }
            }

            // close the shade if it was open
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
            visibilityChanged(false);
        }
    }

    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    protected void visibilityChanged(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            try {
                mBarService.onPanelRevealed();
            } catch (RemoteException ex) {
                // Won't fail unless the world has ended.
            }
        }
    }

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    void handleNotificationError(IBinder key, StatusBarNotification n, String message) {
        removeNotification(key);
        try {
            mBarService.onNotificationError(n.getPackageName(), n.getTag(), n.getId(), n.getUid(), n.getInitialPid(), message);
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    protected StatusBarNotification removeNotificationViews(IBinder key) {
        NotificationData.Entry entry = mNotificationData.remove(key);
        if (entry == null) {
            Log.w(TAG, "removeNotification for unknown key: " + key);
            return null;
        }
        // Remove the expanded view.
        ViewGroup rowParent = (ViewGroup)entry.row.getParent();
        if (rowParent != null) rowParent.removeView(entry.row);
        updateExpansionStates();
        updateNotificationIcons();
        maybeCollapseAfterNotificationRemoval(entry.row.isUserDismissed());

        // If a notif is on hover list or currently showed in hover,
        // remove (hide) it if system does.
        mHover.removeNotification(entry);

        return entry.notification;
    }

    protected void maybeCollapseAfterNotificationRemoval(boolean userDismissed) {
        if (mAutoCollapseBehaviour == Settings.System.STATUS_BAR_COLLAPSE_NEVER) {
            return;
        }
        if (!isNotificationPanelFullyVisible() || isTrackingNotificationPanel()) {
            return;
        }

        boolean collapseDueToEmpty =
                mAutoCollapseBehaviour == Settings.System.STATUS_BAR_COLLAPSE_IF_EMPTIED
                && mNotificationData.size() == 0;
        boolean collapseDueToNoClearable =
                mAutoCollapseBehaviour == Settings.System.STATUS_BAR_COLLAPSE_IF_NO_CLEARABLE
                && !mNotificationData.hasClearableItems();

        if (userDismissed && (collapseDueToEmpty || collapseDueToNoClearable)) {
            mHandler.removeCallbacks(mPanelCollapseRunnable);
            mHandler.postDelayed(mPanelCollapseRunnable, COLLAPSE_AFTER_DISMISS_DELAY);
        } else if (mNotificationData.size() == 0) {
            mHandler.removeCallbacks(mPanelCollapseRunnable);
            mHandler.postDelayed(mPanelCollapseRunnable, COLLAPSE_AFTER_REMOVE_DELAY);
        }
    }

    protected NotificationData.Entry createNotificationViews(IBinder key,
            StatusBarNotification notification) {
        if (DEBUG) {
            Log.d(TAG, "createNotificationViews(key=" + key + ", notification=" + notification);
        }
        // Construct the icon.
        final StatusBarIconView iconView = new StatusBarIconView(mContext,
                notification.getPackageName() + "/0x" + Integer.toHexString(notification.getId()),
                notification.getNotification());
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        final StatusBarIcon ic = new StatusBarIcon(notification.getPackageName(),
                notification.getUser(),
                    notification.getNotification().icon,
                    notification.getNotification().iconLevel,
                    notification.getNotification().number,
                    notification.getNotification().tickerText);
        if (!iconView.set(ic)) {
            handleNotificationError(key, notification, "Couldn't create icon: " + ic);
            return null;
        }
        // Construct the expanded view.
        NotificationData.Entry entry = new NotificationData.Entry(key, notification, iconView);
        if (!inflateViews(entry, mPile)) {
            handleNotificationError(key, notification, "Couldn't expand RemoteViews for: "
                    + notification);
            return null;
        }
        return entry;
    }

    protected void addNotificationViews(NotificationData.Entry entry) {
        if (entry == null) {
            return;
        }
        // Add the expanded view and icon.
        int pos = mNotificationData.add(entry);
        if (DEBUG) {
            Log.d(TAG, "addNotificationViews: added at " + pos);
        }
        updateExpansionStates();
        updateNotificationIcons();
            // screen on - check if hover is enabled
        if (mNotificationHelper.isHoverEnabled()) {
            mHover.setNotification(entry, true);
        } else {
            // We pass this to hover here only if it doesn't show
            mHover.addStatusBarNotification(entry.notification);
        }
        mHandler.removeCallbacks(mPanelCollapseRunnable);
    }

    private void addNotificationViews(IBinder key, StatusBarNotification notification) {
        addNotificationViews(createNotificationViews(key, notification));
    }

    public void updateExpansionStates() {
        int N = mNotificationData.size();
        for (int i = 0; i < N; i++) {
            NotificationData.Entry entry = mNotificationData.get(i);
            if (!entry.row.isUserLocked()) {
                if (i == (N-1)) {
                    if (DEBUG) Log.d(TAG, "expanding top notification at " + i);
                    entry.row.setExpanded(true);
                } else {
                    if (!entry.row.isUserExpanded()) {
                        if (DEBUG) Log.d(TAG, "collapsing notification at " + i);
                        entry.row.setExpanded(false);
                    } else {
                        if (DEBUG) Log.d(TAG, "ignoring user-modified notification at " + i);
                    }
                }
            } else {
                if (DEBUG) Log.d(TAG, "ignoring notification being held by user at " + i);
            }
        }
    }

    public void animateStatusBarOut() {
        // should be overridden
    }

    public void animateStatusBarIn() {
        // should be overridden
    }

    protected abstract void haltTicker();
    protected abstract void setAreThereNotifications();
    protected abstract void updateNotificationIcons();
    protected abstract void tick(IBinder key, StatusBarNotification n, boolean firstTime);
    protected abstract void updateExpandedViewPos(int expandedPosition);
    protected abstract int getExpandedViewMaxHeight();
    protected abstract boolean isNotificationPanelFullyVisible();
    protected abstract boolean isTrackingNotificationPanel();
    protected abstract boolean shouldDisableNavbarGestures();
    public abstract boolean isExpandedVisible();

    protected boolean isTopNotification(ViewGroup parent, NotificationData.Entry entry) {
        return parent != null && parent.indexOfChild(entry.row) == 0;
    }

    public void updateNotification(IBinder key, StatusBarNotification notification) {
        if (DEBUG) Log.d(TAG, "updateNotification(" + key + " -> " + notification + ")");

        final NotificationData.Entry oldEntry = mNotificationData.findByKey(key);
        if (oldEntry == null) {
            Log.w(TAG, "updateNotification for unknown key: " + key);
            return;
        }

        final StatusBarNotification oldNotification = oldEntry.notification;

        // XXX: modify when we do something more intelligent with the two content views
        final RemoteViews oldContentView = oldNotification.getNotification().contentView;
        final RemoteViews contentView = notification.getNotification().contentView;
        final RemoteViews oldBigContentView = oldNotification.getNotification().bigContentView;
        final RemoteViews bigContentView = notification.getNotification().bigContentView;

        if (DEBUG) {
            Log.d(TAG, "old notification: when=" + oldNotification.getNotification().when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " expanded=" + oldEntry.expanded
                    + " contentView=" + oldContentView
                    + " bigContentView=" + oldBigContentView
                    + " rowParent=" + oldEntry.row.getParent());
            Log.d(TAG, "new notification: when=" + notification.getNotification().when
                    + " ongoing=" + oldNotification.isOngoing()
                    + " contentView=" + contentView
                    + " bigContentView=" + bigContentView);
        }

        // Can we just reapply the RemoteViews in place?  If when didn't change, the order
        // didn't change.

        // 1U is never null
        boolean contentsUnchanged = oldEntry.expanded != null
                && contentView.getPackage() != null
                && oldContentView.getPackage() != null
                && oldContentView.getPackage().equals(contentView.getPackage())
                && oldContentView.getLayoutId() == contentView.getLayoutId();
        // large view may be null
        boolean bigContentsUnchanged =
                (oldEntry.getBigContentView() == null && bigContentView == null)
                || ((oldEntry.getBigContentView() != null && bigContentView != null)
                    && bigContentView.getPackage() != null
                    && oldBigContentView.getPackage() != null
                    && oldBigContentView.getPackage().equals(bigContentView.getPackage())
                    && oldBigContentView.getLayoutId() == bigContentView.getLayoutId());
        ViewGroup rowParent = (ViewGroup) oldEntry.row.getParent();
        boolean orderUnchanged = notification.getNotification().when== oldNotification.getNotification().when
                && notification.getScore() == oldNotification.getScore();
                // score now encompasses/supersedes isOngoing()

        boolean updateTicker = (notification.getNotification().tickerText != null
                && !TextUtils.equals(notification.getNotification().tickerText,
                        oldEntry.notification.getNotification().tickerText)) && !mHoverActive;
        boolean isTopAnyway = isTopNotification(rowParent, oldEntry);
        if (contentsUnchanged && bigContentsUnchanged && (orderUnchanged || isTopAnyway)) {
            if (DEBUG) Log.d(TAG, "reusing notification for key: " + key);
            oldEntry.notification = notification;
            try {
                updateNotificationViews(oldEntry, notification);

                if (ENABLE_HEADS_UP && mInterruptingNotificationEntry != null
                        && oldNotification == mInterruptingNotificationEntry.notification) {
                    if (!shouldInterrupt(notification)) {
                        if (DEBUG) Log.d(TAG, "no longer interrupts!");
                        mHandler.sendEmptyMessage(MSG_HIDE_HEADS_UP);
                    } else {
                        if (DEBUG) Log.d(TAG, "updating the current heads up:" + notification);
                        mInterruptingNotificationEntry.notification = notification;
                        updateNotificationViews(mInterruptingNotificationEntry, notification, true);
                    }
                }

                // Update the icon.
                final StatusBarIcon ic = new StatusBarIcon(notification.getPackageName(),
                        notification.getUser(),
                        notification.getNotification().icon, notification.getNotification().iconLevel,
                        notification.getNotification().number,
                        notification.getNotification().tickerText);
                if (!oldEntry.icon.set(ic)) {
                    handleNotificationError(key, notification, "Couldn't update icon: " + ic);
                    return;
                }
                updateExpansionStates();
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Log.w(TAG, "Couldn't reapply views for package " + contentView.getPackage(), e);
                removeNotificationViews(key);
                addNotificationViews(key, notification);
            }
        } else {
            if (DEBUG) Log.d(TAG, "not reusing notification for key: " + key);
            if (DEBUG) Log.d(TAG, "contents was " + (contentsUnchanged ? "unchanged" : "changed"));
            if (DEBUG) Log.d(TAG, "order was " + (orderUnchanged ? "unchanged" : "changed"));
            if (DEBUG) Log.d(TAG, "notification is " + (isTopAnyway ? "top" : "not top"));
            final boolean wasExpanded = oldEntry.row.isUserExpanded();
            removeNotificationViews(key);
            addNotificationViews(key, notification);  // will also replace the heads up
            if (wasExpanded) {
                final NotificationData.Entry newEntry = mNotificationData.findByKey(key);
                newEntry.row.setExpanded(true);
                newEntry.row.setUserExpanded(true);
            }
        }

        // Update the veto button accordingly (and as a result, whether this row is
        // swipe-dismissable)
        updateNotificationVetoButton(oldEntry.row, notification);

        // Is this for you?
        boolean isForCurrentUser = notificationIsForCurrentUser(notification);
        if (DEBUG) Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");

        // Restart the ticker if it's still running
        if (updateTicker && isForCurrentUser) {
            haltTicker();
            tick(key, notification, false);
        }

        // Recalculate the position of the sliding windows and the titles.
        setAreThereNotifications();
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
    }

    private void updateNotificationViews(NotificationData.Entry entry,
            StatusBarNotification notification) {
        updateNotificationViews(entry, notification, false);
    }

    private void updateNotificationViews(NotificationData.Entry entry,
            StatusBarNotification notification, boolean headsUp) {
        final RemoteViews contentView = notification.getNotification().contentView;
        final RemoteViews bigContentView = notification.getNotification().bigContentView;
        // Reapply the RemoteViews
        contentView.reapply(mContext, entry.expanded, mOnClickHandler);
        if (bigContentView != null && entry.getBigContentView() != null) {
            bigContentView.reapply(mContext, entry.getBigContentView(), mOnClickHandler);
        }
        // update the contentIntent
        final PendingIntent contentIntent = notification.getNotification().contentIntent;
        if (contentIntent != null) {
            final View.OnClickListener listener =
                    mNotificationHelper.getNotificationClickListener(entry, headsUp);
            entry.content.setOnClickListener(listener);
        } else {
            entry.content.setOnClickListener(null);
        }
        mHover.addStatusBarNotification(entry.notification);
            // screen on - check if hover is enabled
        if (mNotificationHelper.isHoverEnabled()) {
            mHover.setNotification(entry, true);
        } else {
            // We pass this to hover here only if it doesn't show
            mHover.addStatusBarNotification(entry.notification);
        }
    }

    protected void notifyHeadsUpScreenOn(boolean screenOn) {
        if (!screenOn && mInterruptingNotificationEntry != null) {
            mHandler.sendEmptyMessage(MSG_ESCALATE_HEADS_UP);
        }
    }

    protected boolean shouldInterrupt(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();

        // check if notification from the package is blacklisted first
        if (isPackageBlacklisted(sbn.getPackageName())) {
            return false;
        }

        // some predicates to make the boolean logic legible
        boolean isNoisy = (notification.defaults & Notification.DEFAULT_SOUND) != 0
                || (notification.defaults & Notification.DEFAULT_VIBRATE) != 0
                || notification.sound != null
                || notification.vibrate != null;
        boolean isHighPriority = sbn.getScore() >= INTERRUPTION_THRESHOLD;
        boolean isFullscreen = notification.fullScreenIntent != null;
        boolean isAllowed = notification.extras.getInt(Notification.EXTRA_AS_HEADS_UP,
                Notification.HEADS_UP_ALLOWED) != Notification.HEADS_UP_NEVER;
        boolean isOngoing = sbn.isOngoing();

        final KeyguardTouchDelegate keyguard = KeyguardTouchDelegate.getInstance(mContext);
        boolean keyguardNotVisible = !keyguard.isShowingAndNotHidden()
                && !keyguard.isInputRestricted();

        final InputMethodManager inputMethodManager = (InputMethodManager)
                mContext.getSystemService(Context.INPUT_METHOD_SERVICE);

        boolean isIMEShowing = inputMethodManager.isImeShowing();

        boolean interrupt = (isFullscreen || (isHighPriority && isNoisy))
                && isAllowed
                && keyguardNotVisible
                && !isOngoing
                && !isIMEShowing
                && mPowerManager.isScreenOn();
        try {
            interrupt = interrupt && !mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.d(TAG, "failed to query dream manager", e);
        }
        // its below our threshold priority, we might want to always display
        // notifications from certain apps
        if (!isHighPriority && keyguardNotVisible && !isOngoing && !isIMEShowing) {
            // However, we don't want to interrupt if we're in an application that is
            // in Do Not Disturb
            if (!isPackageInDnd(getTopLevelPackage())) {
                return true;
            }
        }

        if (DEBUG) Log.d(TAG, "interrupt: " + interrupt);
        return interrupt;
    }

    private String getTopLevelPackage() {
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo > taskInfo = am.getRunningTasks(1);
        ComponentName componentInfo = taskInfo.get(0).topActivity;
        return componentInfo.getPackageName();
    }

    private boolean isPackageInDnd(String packageName) {
        return mDndList.contains(packageName);
    }

    private boolean isPackageBlacklisted(String packageName) {
        return mBlacklist.contains(packageName);
    }

    private void splitAndAddToArrayList(ArrayList<String> arrayList,
                                        String baseString, String separator) {
        // clear first
        arrayList.clear();
        if (baseString != null) {
            final String[] array = TextUtils.split(baseString, separator);
            for (String item : array) {
                arrayList.add(item.trim());
            }
        }
    }

    // Q: What kinds of notifications should show during setup?
    // A: Almost none! Only things coming from the system (package is "android") that also
    // have special "kind" tags marking them as relevant for setup (see below).
    protected boolean showNotificationEvenIfUnprovisioned(StatusBarNotification sbn) {
        if ("android".equals(sbn.getPackageName())) {
            if (sbn.getNotification().kind != null) {
                for (String aKind : sbn.getNotification().kind) {
                    // IME switcher, created by InputMethodManagerService
                    if ("android.system.imeswitcher".equals(aKind)) return true;
                    // OTA availability & errors, created by SystemUpdateService
                    if ("android.system.update".equals(aKind)) return true;
                }
            }
        }
        return false;
    }

    public boolean inKeyguardRestrictedInputMode() {
        return KeyguardTouchDelegate.getInstance(mContext).isInputRestricted();
    }

    public void setInteracting(int barWindow, boolean interacting) {
        // hook for subclasses
    }

    public void destroy() {
        if (mSearchPanelView != null) {
            mWindowManager.removeViewImmediate(mSearchPanelView);
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    protected void addActiveDisplayView() {
        if (mActiveDisplayView == null) {
            mActiveDisplayView = (ActiveDisplayView) View.inflate(mContext, R.layout.active_display, null);
            mActiveDisplayView.setBar(this);
            mWindowManager.addView(mActiveDisplayView, getActiveDisplayViewLayoutParams());
        }
    }

    protected void removeActiveDisplayView() {
        if (mActiveDisplayView != null) {
            mWindowManager.removeView(mActiveDisplayView);
        }
    }

    protected WindowManager.LayoutParams getActiveDisplayViewLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                0
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE);
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("ActiveDisplayView");

        return lp;
    }

    protected void addAppCircleSidebar() {
        if (mAppCircleSidebar == null) {
            mAppCircleSidebar = (AppCircleSidebar) View.inflate(mContext, R.layout.app_circle_sidebar, null);
            mWindowManager.addView(mAppCircleSidebar, getAppCircleSidebarLayoutParams());
        }
    }

    protected void removeAppCircleSidebar() {
        if (mAppCircleSidebar != null) {
            mWindowManager.removeView(mAppCircleSidebar);
        }
    }

    protected WindowManager.LayoutParams getAppCircleSidebarLayoutParams() {
        int maxWidth =
                mContext.getResources().getDimensionPixelSize(R.dimen.app_sidebar_trigger_width);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                maxWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                0
                | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.setTitle("AppCircleSidebar");

        return lp;
    }

    class SidebarObserver extends ContentObserver {
        SidebarObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.APP_SIDEBAR_POSITION), false, this);
            update();
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void update() {
            ContentResolver resolver = mContext.getContentResolver();
            int sidebarPosition = Settings.System.getInt(
                    resolver, Settings.System.APP_SIDEBAR_POSITION, AppSidebar.SIDEBAR_POSITION_LEFT);
            if (sidebarPosition != mSidebarPosition) {
                mSidebarPosition = sidebarPosition;
                mWindowManager.updateViewLayout(mAppSidebar, getAppSidebarLayoutParams(sidebarPosition));
            }
        }
    }

    protected void addSidebarView() {
        mAppSidebar = (AppSidebar)View.inflate(mContext, R.layout.app_sidebar, null);
        mWindowManager.addView(mAppSidebar, getAppSidebarLayoutParams(mSidebarPosition));
    }

    protected void removeSidebarView() {
        if (mAppSidebar != null)
            mWindowManager.removeView(mAppSidebar);
    }

    protected WindowManager.LayoutParams getAppSidebarLayoutParams(int position) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                0
                | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        lp.gravity = Gravity.TOP;// | Gravity.FILL_VERTICAL;
        lp.gravity |= position == AppSidebar.SIDEBAR_POSITION_LEFT ? Gravity.LEFT : Gravity.RIGHT;
        lp.setTitle("AppSidebar");

        return lp;
    }

    @ChaosLab(name="GestureAnywhere", classification=Classification.NEW_METHOD)
    protected void addGestureAnywhereView() {
        mGestureAnywhereView = (GestureAnywhereView)View.inflate(
                mContext, R.layout.gesture_anywhere_overlay, null);
        mWindowManager.addView(mGestureAnywhereView, getGestureAnywhereViewLayoutParams(Gravity.LEFT));
        mGestureAnywhereView.setStatusBar(this);
    }

    @ChaosLab(name="GestureAnywhere", classification=Classification.NEW_METHOD)
    protected void removeGestureAnywhereView() {
        if (mGestureAnywhereView != null)
            mWindowManager.removeView(mGestureAnywhereView);
    }

    @ChaosLab(name="GestureAnywhere", classification=Classification.NEW_METHOD)
    protected WindowManager.LayoutParams getGestureAnywhereViewLayoutParams(int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                0
                | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        lp.gravity = Gravity.TOP | gravity;
        lp.setTitle("GestureAnywhereView");

        return lp;
    }

    private boolean isScreenPortrait() {
        return mContext.getResources()
            .getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    Runnable mKillTask = new Runnable() {
        public void run() {
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            String defaultHomePackage = "com.android.launcher";
            intent.addCategory(Intent.CATEGORY_HOME);
            final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
            if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
                defaultHomePackage = res.activityInfo.packageName;
            }
            boolean targetKilled = false;
            final ActivityManager am = (ActivityManager) mContext
                    .getSystemService(Activity.ACTIVITY_SERVICE);
            List<RunningAppProcessInfo> apps = am.getRunningAppProcesses();
            for (RunningAppProcessInfo appInfo : apps) {
                int uid = appInfo.uid;
                // Make sure it's a foreground user application (not system,
                // root, phone, etc.)
                if (uid >= Process.FIRST_APPLICATION_UID && uid <= Process.LAST_APPLICATION_UID
                        && appInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    if (appInfo.pkgList != null && (appInfo.pkgList.length > 0)) {
                        for (String pkg : appInfo.pkgList) {
                            if (!pkg.equals("com.android.systemui")
                                    && !pkg.equals(defaultHomePackage)) {
                                am.forceStopPackage(pkg);
                                targetKilled = true;
                                break;
                            }
                        }
                    } else {
                        Process.killProcess(appInfo.pid);
                        targetKilled = true;
                    }
                }
                if (targetKilled) {
                    Toast.makeText(mContext,
                        R.string.app_killed_message, Toast.LENGTH_SHORT).show();
                    break;
                }
            }
        }
    };

    final Runnable mScreenshotTimeout = new Runnable() {
        @Override
        public void run() {
            synchronized (mScreenshotLock) {
                if (mScreenshotConnection != null) {
                    mContext.unbindService(mScreenshotConnection);
                    mScreenshotConnection = null;
                }
            }
        }
    };

    private final Object mScreenshotLock = new Object();
    private ServiceConnection mScreenshotConnection = null;
    private Handler mHDL = new Handler();

    private void takeScreenshot() {
        synchronized (mScreenshotLock) {
            if (mScreenshotConnection != null) {
                return;
            }
            ComponentName cn = new ComponentName("com.android.systemui",
                    "com.android.systemui.screenshot.TakeScreenshotService");
            Intent intent = new Intent();
            intent.setComponent(cn);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mScreenshotLock) {
                        if (mScreenshotConnection != this) {
                            return;
                        }
                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, 1);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(mHDL.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection == myConn) {
                                        mContext.unbindService(mScreenshotConnection);
                                        mScreenshotConnection = null;
                                        mHDL.removeCallbacks(mScreenshotTimeout);
                                    }
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = msg.arg2 = 0;

                        /*
                         * remove for the time being if (mStatusBar != null &&
                         * mStatusBar.isVisibleLw()) msg.arg1 = 1; if
                         * (mNavigationBar != null &&
                         * mNavigationBar.isVisibleLw()) msg.arg2 = 1;
                         */

                        /* wait for the dialog box to close */
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                        }

                        /* take the screenshot */
                        try {
                            messenger.send(msg);
                        } catch (RemoteException e) {
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
            };
            if (mContext.bindService(intent, conn, mContext.BIND_AUTO_CREATE)) {
                mScreenshotConnection = conn;
                mHDL.postDelayed(mScreenshotTimeout, 10000);
            }
        }
    }

    private void getLastApp() {
        int lastAppId = 0;
        int looper = 1;
        String packageName;
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        final ActivityManager am = (ActivityManager) mContext
                .getSystemService(Activity.ACTIVITY_SERVICE);
        String defaultHomePackage = "com.android.launcher";
        intent.addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo res = mContext.getPackageManager().resolveActivity(intent, 0);
        if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
            defaultHomePackage = res.activityInfo.packageName;
        }
        List <ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
        // lets get enough tasks to find something to switch to
        // Note, we'll only get as many as the system currently has - up to 5
        while ((lastAppId == 0) && (looper < tasks.size())) {
            packageName = tasks.get(looper).topActivity.getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals("com.android.systemui")) {
                lastAppId = tasks.get(looper).id;
            }
            looper++;
        }
        if (lastAppId != 0) {
            am.moveTaskToFront(lastAppId, am.MOVE_TASK_NO_USER_ACTION);
        }
    }
}
