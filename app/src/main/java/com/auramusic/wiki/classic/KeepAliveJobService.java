package com.auramusic.wiki.classic;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * 持久化保活任务的执行体。
 *
 * <p>每次触发只做两件事：确保前台轮询服务在运行、续订下一次离线检查闹钟。
 * 具体是否真的启动，由 {@link AuditPollingService#startIfEnabled(Context)} 依据
 * 「后台提醒开关 + 是否已登录」自行判定，因此本类不需要重复业务判断。
 *
 * <p>不做耗时操作，立即 {@code jobFinished}，避免被系统判定为超时。
 */
public class KeepAliveJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        AuditPollingService.startIfEnabled(this);
        // 服务若已在运行，startIfEnabled 不会触发新一轮检查，这里主动补一次
        AuditPollingService.checkNow(this);
        AuditAlarmReceiver.schedule(this);
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 被系统中断时要求重新调度，提高存活率
        return true;
    }
}
