package com.snda.mymarket.providers.downloads;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

class RealSystemFacade implements SystemFacade {
	
	private static final int CORE_POOL_SIZE = Constants.CORE_POOL_SIZE;
	private static final int MAXIMUM_POOL_SIZE = Constants.MAXIMUM_POOL_SIZE;
	private static final int KEEP_ALIVE = Constants.KEEP_ALIVE;
	
	private Context mContext;
	
	private NotificationManager mNotificationManager;
	
	// 2 GB
	private static final long DOWNLOAD_MAX_BYTES_OVER_MOBILE = 2 * 1024 * 1024 * 1024L;
	// 1 GB
	private static final long DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE = 1024 * 1024 * 1024;
	


    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(Constants.MAXIMUM_WORK_QUEUE_SIZE);

    /**
     * An {@link Executor} that can be used to execute tasks in parallel.
     */
    public static final  ThreadPoolExecutor executor = new ThreadPoolExecutor(
    		CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, 10, TimeUnit.SECONDS,
    		sPoolWorkQueue);

	public RealSystemFacade(Context context) {
		mContext = context;
		mNotificationManager = (NotificationManager) mContext
				.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	public Integer getActiveNetworkType() {
		ConnectivityManager connectivity = (ConnectivityManager) mContext
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (connectivity == null) {
			Log.w(Constants.TAG, "couldn't get connectivity manager");
			return null;
		}

		NetworkInfo activeInfo = connectivity.getActiveNetworkInfo();
		if (activeInfo == null) {
			if (Constants.LOGVV) {
				Log.v(Constants.TAG, "network is not available");
			}
			return null;
		}
		return activeInfo.getType();
	}

	public boolean isNetworkRoaming() {
		ConnectivityManager connectivity = (ConnectivityManager) mContext
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (connectivity == null) {
			Log.w(Constants.TAG, "couldn't get connectivity manager");
			return false;
		}

		NetworkInfo info = connectivity.getActiveNetworkInfo();
		boolean isMobile = (info != null && info.getType() == ConnectivityManager.TYPE_MOBILE);
		final TelephonyManager mgr = (TelephonyManager) mContext
				.getSystemService(Context.TELEPHONY_SERVICE);
		boolean isRoaming = isMobile && mgr.isNetworkRoaming();
		if (Constants.LOGVV && isRoaming) {
			Log.v(Constants.TAG, "network is roaming");
		}
		return isRoaming;
	}

	public Long getMaxBytesOverMobile() {
		return DOWNLOAD_MAX_BYTES_OVER_MOBILE;
	}

	@Override
	public Long getRecommendedMaxBytesOverMobile() {
		return DOWNLOAD_RECOMMENDED_MAX_BYTES_OVER_MOBILE;
	}

	@Override
	public void sendBroadcast(Intent intent) {
		mContext.sendBroadcast(intent);
	}

	@Override
	public boolean userOwnsPackage(int uid, String packageName)
			throws NameNotFoundException {
		return mContext.getPackageManager().getApplicationInfo(packageName, 0).uid == uid;
	}


	@Override
	public void startThread(Thread thread) {
		thread.start();
	}

	@Override
	public Future<?> runOnThreadPool(Runnable command) {
		return executor.submit(command);
	}

	@Override
	public void clearThreadPool() {
		synchronized (sPoolWorkQueue) {
			sPoolWorkQueue.clear();
		}
	}
	
}
