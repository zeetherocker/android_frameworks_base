package com.android.internal.widget;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.IWindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;

import com.android.internal.R;

@SuppressLint("ViewConstructor")
/**
 * @hide
 */
public class FloatingWindowView extends RelativeLayout {

    private static final int ID_OVERLAY_VIEW = 1000000;
    private static final int ID_NOTIFICATION_RESTORE = 22222222;
    private static final String INTENT_APP_ID = "id";
    private static final String INTENT_APP_TOKEN = "token";

    private final int SNAP_LEFT = 1;
    private final int SNAP_TOP = 2;
    private final int SNAP_RIGHT = 3;
    private final int SNAP_BOTTOM = 4;

    private Resources mResource;
    private RelativeLayout mTitleBarHeader;
    private ImageButton mTitleBarMin;
    private ImageButton mTitleBarMax;
    private ImageButton mTitleBarClose;
    private ImageButton mTitleBarMore;
    private View mContentViews;
    private View mDividerViews;

    private static IWindowManager iWindowManager;
    private static ActivityManager iActivityManager;
    private static Context mSystemContext; // SystemUI Context

    private static final String PACKAGE_NAME = FloatingWindowView.class.getPackage().getName();
    private static final String CHANGE_APP_FOCUS = PACKAGE_NAME + ".CHANGE_APP_FOCUS";
    private static final String REMOVE_NOTIFICATION_RESTORE = PACKAGE_NAME + ".REMOVE_NOTIFICATION_RESTORE.";

    public FloatingWindowView(final Activity activity, int height) {
        super(activity);
        mResource = activity.getResources();

	XmlResourceParser parser = mResource.getLayout(R.layout.floating_window_layout);
        activity.getLayoutInflater().inflate(parser, this);

        setId(ID_OVERLAY_VIEW);
        setIsRootNamespace(false);

        mContentViews = findViewById(R.id.floating_window_background);
        mContentViews.bringToFront();

	setIsRootNamespace(true);

	final FrameLayout decorView =
                    (FrameLayout) activity.getWindow().peekDecorView().getRootView();

        View child = decorView.getChildAt(0);
        FrameLayout.LayoutParams params =
                                 (FrameLayout.LayoutParams) child.getLayoutParams();
        params.setMargins(0, height, 0, 0);
        child.setLayoutParams(params);

        mTitleBarHeader = (RelativeLayout) findViewByIdHelper(this, R.id.floating_window_titlebar,
                                               "floating_window_titlebar");
        mTitleBarMore = (ImageButton) findViewByIdHelper(mTitleBarHeader, R.id.floating_window_more,
                                               "floating_window_more");
        mTitleBarClose = (ImageButton) findViewByIdHelper(mTitleBarHeader, R.id.floating_window_close,
                                               "floating_window_close");
        mTitleBarMax = (ImageButton) findViewByIdHelper(mTitleBarHeader, R.id.floating_window_max,
                                               "floating_window_max");
        mTitleBarMin = (ImageButton) findViewByIdHelper(mTitleBarHeader, R.id.floating_window_min,
                                               "floating_window_min");
        mDividerViews = findViewByIdHelper(mTitleBarHeader, R.id.floating_window_line,
                                               "floating_window_line");

        if (mTitleBarHeader == null
            || mTitleBarClose == null
            || mTitleBarMore == null
            || mTitleBarMax == null
            || mTitleBarMin == null
            || mDividerViews == null) {
            return;
        }

        mTitleBarClose.setImageDrawable(mResource.getDrawable(R.drawable.ic_floating_window_close));
        mTitleBarClose.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                activity.finish();
            }
        });

        mTitleBarMax.setImageDrawable(mResource.getDrawable(R.drawable.ic_floating_window_max));
        mTitleBarMax.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                activity.setFullscreenApp();
            }
        });

        mTitleBarMin.setImageDrawable(mResource.getDrawable(R.drawable.ic_floating_window_min));
        mTitleBarMin.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                minimizeAndShowNotification(activity);
            }
        });

        mTitleBarMore.setImageDrawable(mResource.getDrawable(R.drawable.ic_floating_window_more));

        final String menu_item1 = mResource.getString(R.string.floating_window_snap_top);
        final String menu_item2 = mResource.getString(R.string.floating_window_snap_bottom);
        final String menu_item3 = mResource.getString(R.string.floating_window_snap_left);
        final String menu_item4 = mResource.getString(R.string.floating_window_snap_right);
        final String menu_item5 = mResource.getString(R.string.floating_window_minimize);

        final PopupMenu popupMenu = new PopupMenu(mTitleBarMore.getContext(), mTitleBarMore);
        Menu menu = popupMenu.getMenu();
        menu.add(menu_item1);
        menu.add(menu_item2);
        menu.add(menu_item3);
        menu.add(menu_item4);
        menu.add(menu_item5);

        mTitleBarMore.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                      @Override
                      public boolean onMenuItemClick(MenuItem item) {
                          if (item.getTitle().equals(menu_item1)) {
                              activity.forceSnap(SNAP_TOP);
                          } else if (item.getTitle().equals(menu_item2)) {
                              activity.forceSnap(SNAP_BOTTOM);
                          } else if (item.getTitle().equals(menu_item3)) {
                              activity.forceSnap(SNAP_LEFT);
                          } else if (item.getTitle().equals(menu_item4)) {
                              activity.forceSnap(SNAP_RIGHT);
                          } else if (item.getTitle().equals(menu_item5)) {
                              minimizeAndShowNotification(activity);
                          }
                          return false;
                      }
                });
                popupMenu.show();
            }
        });

        RelativeLayout.LayoutParams header_param =
                              (LayoutParams) mTitleBarHeader.getLayoutParams();
        header_param.height = height;
        mTitleBarHeader.setLayoutParams(header_param);
        mTitleBarHeader.setOnTouchListener(new View.OnTouchListener() {
                 @Override
                 public boolean onTouch(View view, MotionEvent event) {
                     switch (event.getAction()) {
                          case MotionEvent.ACTION_DOWN:
                              activity.setTouchViewDown(event.getX(), event.getY());
                              activity.onUserInteraction();
                              activity.updateFocusApp();
                              if (!activity.getChangedPreviousRange()) {
                                  activity.setPreviousTouchRange(event.getRawX(), event.getRawY());
                                  activity.setChangedPreviousRange(true);
                              }
                              break;
                          case MotionEvent.ACTION_MOVE:
                              activity.changeFlagsLayoutParams();
                              activity.setTouchViewMove(event.getRawX(), event.getRawY());
                              if (activity.getRestorePosition()
                                     && activity.moveRangeAboveLimit(event)) {
                                  activity.restoreOldPosition();
                              }
                              activity.showSnap((int) event.getRawX(), (int) event.getRawY());
                              break;
                          case MotionEvent.ACTION_UP:
                              activity.setChangedFlags(false);
                              activity.finishSnap(activity.isValidSnap()
                                           && activity.getTimeoutDone());
                              activity.discardTimeout();
                              activity.setChangedPreviousRange(false);
                              break;
                      }
                      return view.onTouchEvent(event);
                 }
        });

        ViewGroup.LayoutParams divider_param = mDividerViews.getLayoutParams();
        divider_param.height = 2;
        mDividerViews.setLayoutParams(divider_param);
    }

    private View findViewByIdHelper(View view, int id, String tag) {
        View v = view.findViewById(id);
        if (v == null) {
            v = findViewWithTag(view, tag);
        }
        return v;
    }

    private View findViewWithTag(View view, String text) {
        if (view.getTag() instanceof String) {
            if (((String) view.getTag()).equals(text)) {
                return view;
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                 final View child = group.getChildAt(i);
                 final View found = findViewWithTag(child, text);
                 if (found != null) {
                     return found;
                 }
            }
        }
        return null;
    }

    public void setFloatingBackgroundColor(int color) {
        if (mTitleBarHeader == null
            || mContentViews == null) {
            return;
        }
        mContentViews.setBackgroundDrawable(makeOutline(color, 1));
        mTitleBarHeader.setBackgroundColor(color);
    }

    public void setFloatingColorFilter(int color) {
        if (mTitleBarClose == null
            || mTitleBarMax == null
            || mTitleBarMin == null
            || mTitleBarMore == null
            || mDividerViews == null) {
            return;
        }
        mTitleBarMore.setColorFilter(color, Mode.SRC_ATOP);
        mTitleBarMax.setColorFilter(color, Mode.SRC_ATOP);
        mTitleBarMin.setColorFilter(color, Mode.SRC_ATOP);
        mTitleBarClose.setColorFilter(color, Mode.SRC_ATOP);
        mDividerViews.setBackgroundColor(color);
    }

    private ShapeDrawable makeOutline(int color, int thickness) {
        ShapeDrawable rectShapeDrawable = new ShapeDrawable(new RectShape());
        Paint paint = rectShapeDrawable.getPaint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thickness);
        return rectShapeDrawable;
    }

    public static final RelativeLayout.LayoutParams getParams() {
        final RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(0, 0, 0, 0);
        return params;
    }

    private void minimizeAndShowNotification(Activity ac) {
        Intent i = new Intent(CHANGE_APP_FOCUS);
        i.putExtra("token", ac.getActivityToken());
        i.putExtra("id", ac.getTaskId());
        i.putExtra("notification_hide",
                REMOVE_NOTIFICATION_RESTORE + ac.getPackageName());

        ApplicationInfo app_info = ac.getApplication().getApplicationInfo();
        PendingIntent intent = PendingIntent.getBroadcast(ac, 0, i, 
                PendingIntent.FLAG_UPDATE_CURRENT);
        String title = String.format(mResource.getString(R.string.floating_minimize_notif_title),
                app_info.loadLabel(ac.getPackageManager()));

        Notification n  = new Notification.Builder(ac)
                .setContentTitle(title)
                .setContentText(mResource.getString(R.string.floating_minimize_notif_summary))
                .setSmallIcon(app_info.icon)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .setOngoing(true)
                .getNotification();

        final NotificationManager notificationManager = 
          (NotificationManager) ac.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(ID_NOTIFICATION_RESTORE, n); 

        ac.moveTaskToBack(true);

        ac.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notificationManager.cancel(ID_NOTIFICATION_RESTORE);
            }
        }, new IntentFilter(REMOVE_NOTIFICATION_RESTORE + ac.getPackageName()));
    }

    final static BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IBinder token = (IBinder) intent.getExtra("token");
            int taskId = intent.getIntExtra("id", 0);

            iWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
            iActivityManager = (ActivityManager) mSystemContext
                    .getSystemService(Context.ACTIVITY_SERVICE);

            try {
                iWindowManager.setFocusedApp(token, false);
            } catch (Exception e) {
                Log.d("test1", "CANNOT CHANGE APP FOCUS", e);
            }

            final long origId = Binder.clearCallingIdentity();
            try {
                iActivityManager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION);
            } catch (Exception e) {
                Log.e("test1", "Cannot move the activity to front", e);
            }

            Binder.restoreCallingIdentity(origId);
            // Using "messy" boradcast intent since wm and am needs
            // system-specific permission

            String notification_hide = intent.getStringExtra("notification_hide");
            if (notification_hide != null) {
                context.sendBroadcast(new Intent(notification_hide));
            }
        }
    };
}
