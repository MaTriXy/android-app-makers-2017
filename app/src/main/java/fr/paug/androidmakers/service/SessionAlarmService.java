package fr.paug.androidmakers.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.text.format.DateUtils;
import android.util.Log;

import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

import fr.paug.androidmakers.R;
import fr.paug.androidmakers.manager.AgendaRepository;
import fr.paug.androidmakers.model.Room;
import fr.paug.androidmakers.model.ScheduleSlot;
import fr.paug.androidmakers.model.Session;
import fr.paug.androidmakers.ui.activity.MainActivity;
import fr.paug.androidmakers.util.SessionSelector;

public class SessionAlarmService extends IntentService {

    private static final String TAG = "sessionAlarm";

    public static final String ACTION_NOTIFY_SESSION = "NOTIFY_SESSION";

    public static final String ACTION_SCHEDULE_STARRED_BLOCK = "SCHEDULE_STARRED_BLOCK";
    public static final String ACTION_UNSCHEDULE_UNSTARRED_BLOCK = "ACTION_UNSCHEDULE_UNSTARRED_BLOCK";
    public static final String ACTION_SCHEDULE_ALL_STARRED_BLOCKS = "SCHEDULE_ALL_STARRED_BLOCKS";

    public static final String EXTRA_SESSION_ID = "SESSION_ID";
    public static final String EXTRA_SESSION_START = "SESSION_START";
    public static final String EXTRA_SESSION_END = "SESSION_END";
    public static final String EXTRA_NOTIF_TITLE = "NOTIF_TITLE";
    public static final String EXTRA_NOTIF_CONTENT = "NOTIF_CONTENT";

    private static final int NOTIFICATION_LED_ON_MS = 100;
    private static final int NOTIFICATION_LED_OFF_MS = 1000;
    private static final int NOTIFICATION_ARGB_COLOR = 0xff1EB6E1;

    private static final long MILLI_FIVE_MINUTES = 300000;

    private static final long UNDEFINED_VALUE = -1;

    public SessionAlarmService() {
        super("SessionAlarmService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final String action = intent.getAction();
        LOGD(TAG, "Session alarm : " + action);

        if (ACTION_SCHEDULE_ALL_STARRED_BLOCKS.equals(action)) {
            //Scheduling all starred blocks.
            scheduleAllStarredSessions();
            return;
        }

        final long sessionEnd = intent.getLongExtra(SessionAlarmService.EXTRA_SESSION_END, UNDEFINED_VALUE);
        final long sessionStart = intent.getLongExtra(SessionAlarmService.EXTRA_SESSION_START, UNDEFINED_VALUE);
        final int sessionId = intent.getIntExtra(SessionAlarmService.EXTRA_SESSION_ID, 0);

        if (ACTION_NOTIFY_SESSION.equals(action)) {
            final String notifTitle = intent.getStringExtra(SessionAlarmService.EXTRA_NOTIF_TITLE);
            final String notifContent = intent.getStringExtra(SessionAlarmService.EXTRA_NOTIF_CONTENT);
            if (notifTitle == null || notifContent == null) {
                Log.w(TAG, "Title or content of the notification is null.");
                return;
            }
            LOGD(TAG, "Notifying about sessions starting at " +
                    sessionStart + " = " + (new Date(sessionStart)).toString());
            notifySession(sessionId, notifTitle, notifContent);
        } else if (ACTION_SCHEDULE_STARRED_BLOCK.equals(action)) {
            LOGD(TAG, "Scheduling session alarm.");
            LOGD(TAG, "-> Session start: " + sessionStart + " = " + (new Date(sessionStart))
                    .toString());
            LOGD(TAG, "-> Session end: " + sessionEnd + " = " + (new Date(sessionEnd)).toString());
            scheduleAlarm(sessionStart, sessionEnd, sessionId, true);
        } else if (ACTION_UNSCHEDULE_UNSTARRED_BLOCK.equals(action)) {
            LOGD(TAG, "Unscheduling session alarm for id " + sessionId);
            unscheduleAlarm(sessionId);
        }
    }

    void scheduleAllStarredSessions() {
        // need to be sure that the AngendaRepository is loaded in order to schedule all starred sessions
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                final List<ScheduleSlot> scheduleSlots = AgendaRepository.getInstance()
                        .getScheduleSlots();

                // first unschedule all sessions
                // this is done in case the session slot has changed
                for (ScheduleSlot scheduleSlot : scheduleSlots) {
                    unscheduleAlarm(scheduleSlot.sessionId);
                }

                for (String id : SessionSelector.getInstance().getSessionsSelected()) {
                    ScheduleSlot scheduleSlot = AgendaRepository.getInstance().getScheduleSlot(id);
                    if (scheduleSlot != null) {
                        Log.i("SessionAlarmService", scheduleSlot.toString());
                        scheduleAlarm(scheduleSlot.startDate, scheduleSlot.endDate,
                                scheduleSlot.sessionId, false);
                    }
                }
            }
        };
        AgendaRepository.getInstance().load(new AgendaLoadListener(runnable));
    }

    /**
     * Schedule an alarm for a given session that begins at a given time
     * @param sessionStart start time of the slot. The alarm will be fired before this time
     * @param sessionEnd end time of the slot
     * @param sessionId id of the session
     * @param allowLastMinute allow or not the alarm to be set if the delay between the alarm and
     *                        the session start is over.
     */
    private void scheduleAlarm(final long sessionStart, final long sessionEnd, int sessionId,
                               boolean allowLastMinute) {
        final long currentTime = System.currentTimeMillis();

        Log.i("Time", "current: " + currentTime + ", session start: " + sessionStart);

        final long alarmTime = sessionStart - MILLI_FIVE_MINUTES;

        if (allowLastMinute) {
            // If the session is already started, do not schedule system notification.
            if (currentTime > sessionStart) {
                LOGD(TAG, "Not scheduling alarm because target time is in the past: " + sessionStart);
                return;
            }
        } else {
            if (currentTime > alarmTime) {
                LOGD(TAG, "Not scheduling alarm because alarm time is in the past: " + alarmTime);
                return;
            }
        }

        final Session sessionToNotify = AgendaRepository.getInstance().getSession(sessionId);
        final ScheduleSlot slotToNotify = AgendaRepository.getInstance().getScheduleSlot(sessionId);
        if (sessionToNotify == null || slotToNotify == null) {
            Log.w(TAG, "Cannot find session " + sessionId + " either in sessions or in slots");
            return;
        }

        final String sessionDate = DateUtils.formatDateRange(this,
                new Formatter(Locale.getDefault()),
                slotToNotify.startDate,
                slotToNotify.endDate,
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_TIME,
                null).toString();

        final Room room = AgendaRepository.getInstance().getRoom(slotToNotify.room);
        final String roomName = ((room != null) ? room.name : "") + " - ";

        LOGD(TAG, "Scheduling alarm for " + alarmTime + " = " + (new Date(alarmTime)).toString());

        final Intent notifIntent = new Intent(
                ACTION_NOTIFY_SESSION,
                null,
                this,
                SessionAlarmService.class);
        notifIntent.putExtra(EXTRA_SESSION_START, sessionStart);
        LOGD(TAG, "-> Intent extra: session start " + sessionStart);
        notifIntent.putExtra(EXTRA_SESSION_END, sessionEnd);
        LOGD(TAG, "-> Intent extra: session end " + sessionEnd);
        notifIntent.putExtra(EXTRA_SESSION_ID, sessionId);
        notifIntent.putExtra(EXTRA_NOTIF_TITLE, sessionToNotify.title);
        notifIntent.putExtra(EXTRA_NOTIF_CONTENT, roomName + sessionDate);

        PendingIntent pi = PendingIntent.getService(this, sessionId, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        final AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        // Schedule an alarm to be fired to notify user of added sessions are about to begin.
        LOGD(TAG, "-> Scheduling RTC_WAKEUP alarm at " + alarmTime);
        am.set(AlarmManager.RTC_WAKEUP, alarmTime, pi);
    }

    /**
     * Remove the scheduled alarm for a given session
     * @param sessionId the id of the session
     */
    private void unscheduleAlarm(int sessionId) {
        final AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        final Intent notifIntent = new Intent(
                ACTION_NOTIFY_SESSION,
                null,
                this,
                SessionAlarmService.class);
        PendingIntent pi = PendingIntent.getService(this, sessionId, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        am.cancel(pi);
    }

    // Starred sessions are about to begin. Constructs and triggers system notification.
    private void notifySession(int sessionId, @NonNull String notifTitle,
                               @NonNull String notifContent) {
        // Generates the pending intent which gets fired when the user taps on the notification.
        Intent baseIntent = new Intent(this, MainActivity.class);
        baseIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Intent resultIntent = new Intent(this, MainActivity.class);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                .setContentTitle(notifTitle)
                .setContentText(notifContent)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setLights(
                        SessionAlarmService.NOTIFICATION_ARGB_COLOR,
                        SessionAlarmService.NOTIFICATION_LED_ON_MS,
                        SessionAlarmService.NOTIFICATION_LED_OFF_MS)
                .setSmallIcon(R.drawable.ic_event_note_white_24dp)
                .setContentIntent(resultPendingIntent)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        LOGD(TAG, "Now showing notification.");
        notificationManager.notify(sessionId, notificationBuilder.build());
    }

    public static void LOGD(final String tag, String message) {
        Log.d(tag, message);
    }

    private static class AgendaLoadListener implements AgendaRepository.OnLoadListener {
        @NonNull
        private final Runnable runnable;

        private AgendaLoadListener(@NonNull Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void onAgendaLoaded() {
            runnable.run();
            AgendaRepository.getInstance().removeListener(this);
        }
    }
}
