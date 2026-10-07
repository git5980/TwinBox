package com.lody.virtual.client.hook.proxies.job;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.ipc.VJobScheduler;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.JobWorkItemCompat;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Method;
import java.util.List;

import mirror.android.app.job.IJobScheduler;
import mirror.android.content.pm.ParceledListSlice;

/**
 * @author Lody
 * @see android.app.job.JobScheduler
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class JobServiceStub extends BinderInvocationProxy {

    public JobServiceStub() {
        super(IJobScheduler.Stub.asInterface, Context.JOB_SCHEDULER_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new schedule());
        addMethodProxy(new getAllPendingJobs());
        addMethodProxy(new cancelAll());
        addMethodProxy(new cancel());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            addMethodProxy(new getPendingJob());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addMethodProxy(new enqueue());
        }
    }


    private class schedule extends MethodProxy {

        @Override
        public String getMethodName() {
            return "schedule";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            // TwinBox 2.1.20：Android 16 上 IJobScheduler.schedule 前面被插入了一个
            // 新参数（实测 args = [null, JobInfo]，见 twinbox-20261005.log
            // 15:50:41.140：args=[[null, (job:1596167070/video.player.videoplayer/
            // com.google.android.gms.measurement.AppMeasurementJobService)]]）。
            // 2.1.18 的 args[0] 守卫把调用交回系统，系统又以 guest 包名跑不通，
            // Firebase 的 AppMeasurementJobService 一直排不上。改成按类型找
            // JobInfo，签名怎么插参数都不影响。
            Object jobArg = ArrayUtils.getFirst(args, JobInfo.class);
            if (!(jobArg instanceof JobInfo)) {
                // 真对不上签名：交回系统实现自己拒绝，别在宿主侧抛异常
                return method.invoke(who, args);
            }
            return VJobScheduler.get().schedule((JobInfo) jobArg);
        }
    }

    private class getAllPendingJobs extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getAllPendingJobs";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            List res = VJobScheduler.get().getAllPendingJobs();
            if (res == null)
                return null;
            if (BuildCompat.isQ()) {
                return ParceledListSlice.ctorQ.newInstance(res);
            } else {
                return res;
            }
        }
    }

    private class cancelAll extends MethodProxy {

        @Override
        public String getMethodName() {
            return "cancelAll";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            VJobScheduler.get().cancelAll();
            return 0;
        }
    }

    private class cancel extends MethodProxy {

        @Override
        public String getMethodName() {
            return "cancel";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            // TwinBox 2.1.20：Android 16 上 cancel 也多了一个前导参数
            // （实测 args = [null, 1596167070]；见 twinbox-20261005.log
            // 15:50:41.139）。老的 (int) args[0] 对 null 拆箱直接
            // NullPointerException: Integer.intValue() on a null object
            // reference，整个 cancel 走兜底 → 容器侧 job 永远 cancel 不掉。
            int jobIndex = ArrayUtils.indexOfFirst(args, Integer.class);
            if (jobIndex < 0) {
                return method.invoke(who, args);
            }
            int jobId = (Integer) args[jobIndex];
            VJobScheduler.get().cancel(jobId);
            return 0;
        }
    }

    private class getPendingJob extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getPendingJob";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            // TwinBox 2.1.20：同 cancel，Android 16 上第一个参数是新加的前导参数，
            // jobId 不在 args[0]（null）。按类型找第一个 Integer。
            int jobIndex = ArrayUtils.indexOfFirst(args, Integer.class);
            if (jobIndex < 0) {
                return method.invoke(who, args);
            }
            int jobId = (Integer) args[jobIndex];
            return VJobScheduler.get().getPendingJob(jobId);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private class enqueue extends MethodProxy {

        @Override
        public String getMethodName() {
            return "enqueue";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            // TwinBox 2.1.20：同样按类型取参，别假设 JobInfo 一定在 args[0]。
            Object jobArg = ArrayUtils.getFirst(args, JobInfo.class);
            Object workArg = ArrayUtils.getFirst(args, JobWorkItem.class);
            if (!(jobArg instanceof JobInfo) || !(workArg instanceof JobWorkItem)) {
                return method.invoke(who, args);
            }
            JobWorkItem workItem = JobWorkItemCompat.redirect((JobWorkItem) workArg, getAppPkg());
            return VJobScheduler.get().enqueue((JobInfo) jobArg, workItem);
        }
    }
}
