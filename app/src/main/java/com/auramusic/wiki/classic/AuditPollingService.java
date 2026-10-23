package com.auramusic.wiki.classic;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台审核结果轮询服务（C4：基准版本完全缺失的核心差异化能力）。
 *
 * 定时轮询网易云 UGC 贡献列表，若发现新的审核结果（通过 / 部分通过 / 未采纳），
 * 立即推送高优先级通知，解决「退到后台就无法获知审核结果」的痛点。
 */
public class AuditPollingService extends Service {

    public static final String CHANNEL_AUDIT = "wiki_audit_channel";
    public static final String CHANNEL_FOREGROUND = "wiki_fg_service_v2";

    private static final String TAG = "AuditPoller";
    private static final int FG_NOTIFICATION_ID = 999;
    /** 前台回访时的即时检查节流，避免频繁切换页面触发大量请求 */
    private static final long CHECK_THROTTLE_MS = 20_000L;
    private static volatile long lastCheckAt;

    /**
     * 每轮轮询覆盖的类型。
     *
     * <p>「全部」已包含歌曲 / 专辑 / 艺人 / 补充专辑，服务端唯独不返回歌词与翻译
     * （见 {@link NetEaseWikiClient#ALL_EXTRA_TYPES}），因此只需补这两类。
     * 请求数从 5 降到 3，单轮耗时更短，短周期轮询才不会被网络拖慢。
     */
    private static final int[] TYPES_TO_CHECK = {
            NetEaseWikiClient.TYPE_ALL,
            NetEaseWikiClient.TYPE_LYRIC,
            NetEaseWikiClient.TYPE_LYRIC_TRANSLATION
    };

    /**
     * 每轮每种类型扫描的条数。
     *
     * <p>列表按提交时间倒序，只取前 20 条时，早年提交、最近才出结果的条目会被
     * 新提交挤出窗口，表现为「后台漏推，进入应用刷新后才看到」。服务器接受较大的
     * limit（检索页用到了 400），这里放宽到 100，把「晚出结果」的老条目也纳入轮询。
     */
    private static final int POLL_PAGE_SIZE = 100;

    private volatile boolean isRunning = false;
    private Thread pollingThread;
    /**
     * 最近一轮是否因「服务端判定凭据失效」而中止。
     *
     * <p>此前失效后仍按 30 秒重试，常驻通知不消失且持续打接口；现在服务循环看到该标记
     * 会停止自身并取消闹钟 / Job，等待用户重新登录（下一次进入前台时会重新启动）。
     */
    private static volatile boolean credentialInvalid;

    // ------------------------------------------------------------------
    // 启停入口
    // ------------------------------------------------------------------

    public static void startIfEnabled(Context context) {
        if (!WikiPrefs.backgroundAuditEnabled(context)) {
            Log.i(TAG, "后台提醒已关闭，取消服务与调度");
            AuditAlarmReceiver.cancel(context);
            KeepAliveScheduler.cancel(context);
            return;
        }
        // 未配置凭据时启动也拿不到数据，直接跳过可避免无意义的常驻通知
        if (!WikiPrefs.hasCookie(context)) {
            Log.i(TAG, "未登录，跳过后台轮询服务");
            AuditAlarmReceiver.cancel(context);
            KeepAliveScheduler.cancel(context);
            return;
        }
        // 离线兜底：即使进程被系统回收 / 用户清掉最近任务，闹钟仍会唤醒一轮检查
        AuditAlarmReceiver.schedule(context);
        // 更可靠的一层：persisted JobScheduler，重启后由系统自动恢复
        KeepAliveScheduler.schedule(context);
        Intent intent = new Intent(context, AuditPollingService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "后台轮询服务已请求启动");
        } catch (Exception e) {
            // 部分 ROM 在后台限制启动前台服务，此处静默降级：
            // 前台服务不可用时，AlarmManager 仍能在稍后唤醒一轮离线检查。
            Log.w(TAG, "启动前台服务失败，已降级为闹钟兜底", e);
        }
    }

    /**
     * 立即检查一轮（带节流）。
     *
     * <p>用于「回到前台 / 登录后 / 保活任务唤醒」等场景：无需重启服务，
     * 也不必等下一个轮询周期，几秒内即可发现新的审核结果并推送到通知栏。
     */
    static void checkNow(Context context) {
        if (context == null
                || !WikiPrefs.backgroundAuditEnabled(context)
                || !WikiPrefs.hasCookie(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_THROTTLE_MS) {
            return;
        }
        lastCheckAt = now;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                runOnce(app);
            } catch (Throwable e) {
                Log.w(TAG, "即时检查失败", e);
            }
        }, "wiki-audit-now").start();
    }

    public static void restart(Context context) {
        stop(context);
        startIfEnabled(context);
    }

    public static void stop(Context context) {
        AuditAlarmReceiver.cancel(context);
        KeepAliveScheduler.cancel(context);
        try {
            context.stopService(new Intent(context, AuditPollingService.class));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Service 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        try {
            Notification notification = buildForegroundNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14 起必须显式指定类型，且要与 manifest 声明一致
                startForeground(FG_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // startForeground 失败后若继续运行，系统会在超时后抛 RemoteServiceException 崩溃；
            // 主动停止，交给闹钟 / 持久化 Job 兜底。
            Log.w(TAG, "前台通知启动失败，停止服务", e);
            stopSelf();
        }
    }

    /**
     * 用户从最近任务划掉应用时回调。
     *
     * <p>部分 ROM（ColorOS / MIUI）会连带杀掉进程，这里先把闹钟与持久化 Job 续订上，
     * 让系统在稍后仍有机会重新拉起服务。真正的强杀（强行停止）无法绕过。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AuditAlarmReceiver.schedule(this);
        KeepAliveScheduler.schedule(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            startPolling();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // 轮询
    // ------------------------------------------------------------------

    private void startPolling() {
        pollingThread = new Thread(() -> {
            while (isRunning) {
                int intervalMinutes = WikiPrefs.pollingIntervalMinutes(this);
                long startedAt = System.currentTimeMillis();
                boolean ok = false;
                try {
                    ok = runOnce(this);
                } catch (Exception e) {
                    // 单轮失败不影响下一轮，避免服务退出
                    Log.w(TAG, "本轮轮询失败", e);
                }
                if (credentialInvalid) {
                    // 凭据失效：停止服务并取消闹钟 / Job，避免每 30 秒重复注定失败的请求
                    Log.w(TAG, "登录已失效，停止后台轮询，等待用户重新登录");
                    stop(this);
                    break;
                }
                // 扣除本轮耗时：否则「请求 10 秒 + 睡满一个周期」会让实际间隔
                // 越来越长，表现为通知总是晚于设定周期到达。
                long elapsed = System.currentTimeMillis() - startedAt;
                long wait = ok
                        ? Math.max(5_000L, intervalMinutes * 60_000L - elapsed)
                        // 全部请求失败（断网 / Doze 冻结网络）时 30 秒后重试，
                        // 不要傻等一个完整周期，否则一次抖动就会漏掉整轮提醒。
                        : 30_000L;
                Log.i(TAG, "本轮检查" + (ok ? "成功" : "全部失败") + "，耗时 " + elapsed
                        + "ms，下一轮将在 " + (wait / 1000L) + "s 后执行（周期 "
                        + intervalMinutes + " 分钟）");
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "wiki-audit-poller");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    /**
     * 执行一轮审核状态检查，仅对「首次出现且已出结果」的条目发通知。
     *
     * <p>抽为 static 是为了让<b>进程未启动时</b>也能复用：
     * {@link AuditAlarmReceiver} 通过 AlarmManager 在应用未运行 / 进程已被回收时唤醒本方法，
     * 从而在不依赖服务常驻的前提下补足离线提醒能力。
     *
     * <p>并发保护：闹钟、保活任务、前台即时检查可能同时触发，这里串行化整轮检查，
     * 避免同一结果被重复推送或重复写入去重集合。
     *
     * <p>需求 5 修复：首启去重。
     * 旧实现用 {@code getStringSet(KEY_DONE_IDS, new HashSet<>())} 取已知集合，
     * 无法区分「新安装（键不存在）」与「已知集合恰好为空」两种情况：
     * 新装后首轮轮询会把历史全部已审核条目当作新结果逐条推送，造成通知轰炸。
     * 现改为用 {@code sp.contains(KEY_DONE_IDS)} 判定是否首次运行，
     * 首次只「播种」当前已出结果的条目、不发送任何通知。
     *
     * @return 本轮是否至少有一类请求成功（供调用方决定失败后快速重试）
     */
    static synchronized boolean runOnce(Context context) {
        if (!WikiPrefs.hasCookie(context)) {
            return false;
        }
        credentialInvalid = false;
        lastCheckAt = System.currentTimeMillis();
        SharedPreferences sp = WikiPrefs.get(context);
        // 键不存在 = 首次运行（新安装 / 清数据 / 首次开启后台提醒）→ 仅播种不推送
        boolean firstRun = !sp.contains(WikiPrefs.KEY_DONE_IDS);
        Set<String> knownDoneIds = sp.getStringSet(WikiPrefs.KEY_DONE_IDS, new HashSet<>());
        Set<String> updatedDoneIds = new HashSet<>(knownDoneIds);
        // 快照：推送通道已经提醒过的结果（名称 + 状态），本轮不再重复弹系统通知。
        // 必须在写收件箱之前取快照，否则本轮刚写入的未读会被当成「已提醒」而漏推。
        Set<String> delivered = AuditInbox.deliveredFingerprints(context);

        // 「全部」已经涵盖歌曲 / 专辑 / 艺人 / 补充专辑，只需再补歌词与翻译；
        // 请求数由 5 降到 3，单轮耗时更短，短周期轮询才不会被网络拖慢。
        List<Contribution> all = new ArrayList<>();
        boolean anySuccess = false;
        for (int type : TYPES_TO_CHECK) {
            try {
                all.addAll(NetEaseWikiClient.fetchContributions(context, type, 1,
                        POLL_PAGE_SIZE, null));
                anySuccess = true;
            } catch (WikiException e) {
                if (e.getKind() == WikiException.Kind.NO_CREDENTIAL) {
                    // 凭据失效：停止本轮轮询，避免持续无效请求
                    Log.w(TAG, "凭据失效，本轮轮询中止");
                    credentialInvalid = true;
                    return false;
                }
                Log.w(TAG, "拉取贡献列表失败 type=" + type + "：" + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "拉取贡献列表异常 type=" + type, e);
            }
        }

        // 先落库应用内未读，再逐条推送系统通知，两者互不阻塞
        if (!all.isEmpty()) {
            AuditInbox.observe(context, all);
            // 后台轮询同样补齐「未通过原因」归档，用户下次打开应用即可看到；
            // 歌词 / 翻译纠错不会出现在贡献列表，只能靠私信补，因此这里与列表内容无关
            RejectArchive.syncIfNeeded(context);
        }
        // 随轮询周期检查下载目录的自动清理条件
        DownloadStore.maybeAutoClean(context);

        for (Contribution item : all) {
            Contribution.Status status = item.statusEnum();
            if (status == Contribution.Status.PENDING || status == Contribution.Status.UNKNOWN) {
                continue;
            }
            // 去重键用 stableKey：原实现直接用 bizId，同一首歌曲的「歌词」与「翻译」
            // 共用同一 bizId，会被判为同一条而漏掉其中一侧的通知。
            String key = item.stableKey();
            if (knownDoneIds.contains(key)) {
                continue;
            }
            updatedDoneIds.add(key);
            if (!firstRun
                    && !delivered.contains(item.displayName() + "|" + item.status)) {
                NotificationHelper.postAuditResult(context, item.displayName(), status);
                Log.i(TAG, "已推送审核结果通知：" + item.displayName()
                        + " / " + Contribution.statusName(item.status));
            }
        }

        // 首启轮询若全部请求失败，则本次未能完成播种：不写入集合，
        // 留待下一轮重新播种，避免"空集合 + 已存在键"把历史条目误判为新结果。
        if (firstRun && !anySuccess) {
            return false;
        }

        sp.edit().putStringSet(WikiPrefs.KEY_DONE_IDS, updatedDoneIds).apply();
        return anySuccess;
    }

    private void createNotificationChannels() {
        NotificationHelper.ensureChannels(this);
    }

    private android.app.Notification buildForegroundNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle("监听中")
                .setContentText("曲库审核状态监听服务运行中")
                // 最低优先级 + 静默：不发声、不振动、不弹浮动提示，只做常驻状态标识
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                // Android 12+ 前台通知默认延迟展示，这里要求立即出现，保证「常驻」语义
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(pi)
                .build();
    }
}
