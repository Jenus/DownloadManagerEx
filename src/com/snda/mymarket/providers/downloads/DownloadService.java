/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.snda.mymarket.providers.downloads;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.support.v4.util.LongSparseArray;
import android.text.TextUtils;
import android.util.Log;

/**
 * Performs the background downloads requested by applications that use the
 * Downloads provider.
 */
public class DownloadService extends Service {
	private static final boolean DEBUG_LIFECYCLE = false;
	
	private static final String ACTION_PAUSE_ALL = "action_pause_all";

	private static final String EXTRA_IS_PAUSE_ALL = "extra_is_pause_all";

	/**
	 * pause all download.<br>
	 * <b>Note:</b> This method will not pause the downloads which are running
	 * now, but denies to start new downloads.
	 * 
	 * @param context
	 */
	public static void pauseAllDownload(Context context) {
		Intent intent = new Intent(context, DownloadService.class);
		intent.setAction(ACTION_PAUSE_ALL);
		intent.putExtra(EXTRA_IS_PAUSE_ALL, true);
		context.startService(intent);
	}

	/**
	 * resume all download.<br>
	 * <b>Note:</b> This method will not resume the downloads which are running
	 * now, but allows to start new downloads.
	 * 
	 * @param context
	 */
	public static void resumeAllDownload(Context context) {
		Intent intent = new Intent(context, DownloadService.class);
		intent.setAction(ACTION_PAUSE_ALL);
		intent.putExtra(EXTRA_IS_PAUSE_ALL, false);
		context.startService(intent);
	}

	/** Observer to get notified when the content observer's data changes */
	private DownloadManagerContentObserver mObserver;

	/** Class to handle Notification Manager updates */
	private DownloadNotifier mNotifier;
	
	private DownloadScanner mScanner;

	/**
	 * The Service's view of the list of downloads, mapping download IDs to the
	 * corresponding info object. This is kept independently from the content
	 * provider, and the Service only initiates downloads based on this data, so
	 * that it can deal with situation where the data in the content provider
	 * changes or disappears.
	 */
	private LongSparseArray<DownloadInfo> mDownloads = new LongSparseArray<DownloadInfo>();

	/**
	 * The thread that updates the internal download list from the content
	 * provider.
	 */
	private HandlerThread mUpdateThread;
	private Handler mUpdateHandler;
	private volatile int mLastStartId;


	SystemFacade mSystemFacade;
	private AlarmManager mAlarmManager;
	/**
	 * Receives notifications when the data in the content provider changes
	 */
	private class DownloadManagerContentObserver extends ContentObserver {

		public DownloadManagerContentObserver() {
			super(new Handler());
		}

		/**
		 * Receives notification when the data in the observed content provider
		 * changes.
		 */
		public void onChange(final boolean selfChange) {
			if (Constants.LOGVV) {
				Log.v(Constants.TAG,
						"Service ContentObserver received notification");
			}
			updateFromProvider();
		}

	}

	/**
	 * Returns an IBinder instance when someone wants to connect to this
	 * service. Binding to this service is not allowed.
	 * 
	 * @throws UnsupportedOperationException
	 */
	public IBinder onBind(Intent i) {
		throw new UnsupportedOperationException(
				"Cannot bind to Download Manager Service");
	}

	/**
	 * Initializes the service when it is first created
	 */
	public void onCreate() {
		super.onCreate();
		if (Constants.LOGVV) {
			Log.v(Constants.TAG, "Service onCreate");
		}

		if (mSystemFacade == null) {
			mSystemFacade = new RealSystemFacade(this);
		}

		mObserver = new DownloadManagerContentObserver();
		getContentResolver().registerContentObserver(
				Downloads.ALL_DOWNLOADS_CONTENT_URI, true, mObserver);

		mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		mNotifier = new DownloadNotifier(this);
		mNotifier.cancelAll();
		
		mScanner = new DownloadScanner(this);
		
		mUpdateThread = new HandlerThread(Constants.TAG + "-UpdateThread");
		mUpdateThread.start();
		mUpdateHandler = new Handler(mUpdateThread.getLooper(), mUpdateCallback);

		updateFromProvider();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int returnValue = super.onStartCommand(intent, flags, startId);
		if (Constants.LOGVV) {
			Log.v(Constants.TAG, "Service onStart");
		}
		mLastStartId = startId;
		
		if(intent != null){
			String action = intent.getAction();
	
			if (action != null) {
				if (action.equals(ACTION_PAUSE_ALL)) {
					if ( intent.getExtras().getBoolean(EXTRA_IS_PAUSE_ALL)) {
						mSystemFacade.clearThreadPool();
		
						for ( int i=0; i< mDownloads.size(); i++ ){
							mDownloads.valueAt(i).cancelTask();
						}
						return returnValue;
					}
				}
			}
		}
		
		updateFromProvider();
		return returnValue;
	}

	/**
	 * Cleans up when the service is destroyed
	 */
	public void onDestroy() {
		getContentResolver().unregisterContentObserver(mObserver);
		mScanner.shutdown();
		mUpdateThread.quit();
		if (Constants.LOGVV) {
			Log.v(Constants.TAG, "Service onDestroy");
		}
		super.onDestroy();
	}

	/**
	 * Parses data from the content provider into private array
	 */
	private void updateFromProvider() {
		mUpdateHandler.removeMessages(MSG_UPDATE);
		mUpdateHandler.obtainMessage(MSG_UPDATE, mLastStartId, -1)
				.sendToTarget();
	}
	
	/**
	 * Enqueue an {@link #updateLocked()} pass to occur after delay, usually to
	 * catch any finished operations that didn't trigger an update pass.
	 */
	private void enqueueFinalUpdate() {
		mUpdateHandler.removeMessages(MSG_FINAL_UPDATE);
		mUpdateHandler.sendMessageDelayed(mUpdateHandler.obtainMessage(
				MSG_FINAL_UPDATE, mLastStartId, -1), 5 * MINUTE_IN_MILLIS);
	}

	private static final int MSG_UPDATE = 1;
	private static final int MSG_FINAL_UPDATE = 2;
	private Handler.Callback mUpdateCallback = new Handler.Callback() {
		@Override
		public boolean handleMessage(Message msg) {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			final int startId = msg.arg1;
			if (DEBUG_LIFECYCLE)
				Log.v(Constants.TAG, "Updating for startId " + startId);
			// Since database is current source of truth, our "active" status
			// depends on database state. We always get one final update pass
			// once the real actions have finished and persisted their state.
			// TODO: switch to asking real tasks to derive active state
			// TODO: handle media scanner timeouts
			final boolean isActive;
			synchronized (mDownloads) {
				isActive = updateLocked();
			}
			if (msg.what == MSG_FINAL_UPDATE) {
				// Dump thread stacks belonging to pool
				for (Map.Entry<Thread, StackTraceElement[]> entry : Thread
						.getAllStackTraces().entrySet()) {
					if (entry.getKey().getName().startsWith("pool")) {
						Log.d(Constants.TAG,
								entry.getKey() + ": "
										+ Arrays.toString(entry.getValue()));
					}
				}
				Log.wtf(Constants.TAG, "Final update pass triggered, isActive="
						+ isActive + "; someone didn't update correctly.");
			}
			if (isActive) {
				// Still doing useful work, keep service alive. These active
				// tasks will trigger another update pass when they're finished.
				// Enqueue delayed update pass to catch finished operations that
				// didn't trigger an update pass; these are bugs.
				enqueueFinalUpdate();
			} else {
				// No active tasks, and any pending update messages can be
				// ignored, since any updates important enough to initiate tasks
				// will always be delivered with a new startId.
				if (stopSelfResult(startId)) {
					if (DEBUG_LIFECYCLE)
						Log.v(Constants.TAG, "Nothing left; stopped");
					getContentResolver().unregisterContentObserver(mObserver);
					mScanner.shutdown();
					mUpdateThread.quit();
				}
			}
			return true;
		}
	};

	/**
	 * Update {@link #mDownloads} to match {@link DownloadProvider} state.
	 * Depending on current download state it may enqueue {@link DownloadThread}
	 * instances, request {@link DownloadScanner} scans, update user-visible
	 * notifications, and/or schedule future actions with {@link AlarmManager}.
	 * <p>
	 * Should only be called from {@link #mUpdateThread} as after being
	 * requested through {@link #enqueueUpdate()}.
	 * 
	 * @return If there are active tasks being processed, as of the database
	 *         snapshot taken in this update.
	 */
	private boolean updateLocked() {
		final long now = mSystemFacade.currentTimeMillis();
		boolean isActive = false;
		long nextActionMillis = Long.MAX_VALUE;
		final Set<Long> staleIds = new HashSet<Long>(mDownloads.size());
		final ContentResolver resolver = getContentResolver();
		final Cursor cursor = resolver.query(
				Downloads.ALL_DOWNLOADS_CONTENT_URI, null, null, null,
				null);
		try {
			final DownloadInfo.Reader reader = new DownloadInfo.Reader(
					resolver, cursor);
			final int idColumn = cursor
					.getColumnIndexOrThrow(Downloads._ID);
			while (cursor.moveToNext()) {
				final long id = cursor.getLong(idColumn);
				staleIds.remove(id);
				DownloadInfo info = mDownloads.get(id);
				if (info != null) {
					updateDownload(reader, info, now);
				} else {
					info = insertDownloadLocked(reader, now);
				}
				if (info.mDeleted) {
					// Delete download if requested, but only after cleaning up
					if (!TextUtils.isEmpty(info.mMediaProviderUri)) {
						resolver.delete(Uri.parse(info.mMediaProviderUri),
								null, null);
					}
					Helpers.deleteFile(getContentResolver(), info.mId,
							info.mFileName, info.mMimeType);
				} else {
					// Kick off download task if ready
					final boolean activeDownload = info.startIfReady( this.mNotifier );
					final boolean activeScan = info.startScanIfReady(mScanner);
					if (DEBUG_LIFECYCLE && (activeDownload || activeScan) ) {
						Log.v(Constants.TAG, "Download " + info.mId + ": activeDownload="
								+ activeDownload + ", activeScan=" + activeScan);
					}
					isActive |= activeDownload;
					isActive |= activeScan;
				}
				// Keep track of nearest next action
				nextActionMillis = Math.min(info.nextActionMillis(now),
						nextActionMillis);
			}
		} finally {
			cursor.close();
		}
		// Clean up stale downloads that disappeared
		for (Long id : staleIds) {
			deleteDownloadLocked(id);
		}
		// Update notifications visible to user
		mNotifier.updateWith(mDownloads);
		// Set alarm when next action is in future. It's okay if the service
		// continues to run in meantime, since it will kick off an update pass.
		if (nextActionMillis > 0 && nextActionMillis < Long.MAX_VALUE) {
			if (Constants.LOGV) {
				Log.v(Constants.TAG, "scheduling start in " + nextActionMillis + "ms");
			}
			final Intent intent = new Intent(Constants.ACTION_RETRY);
			intent.setClass(this, DownloadReceiver.class);
			mAlarmManager.set(AlarmManager.RTC_WAKEUP, now + nextActionMillis,
					PendingIntent.getBroadcast(this, 0, intent,
							PendingIntent.FLAG_ONE_SHOT));
		}
		return isActive;
	}

	/**
	 * Keeps a local copy of the info about a download, and initiates the
	 * download if appropriate.
	 */
	private DownloadInfo insertDownloadLocked(DownloadInfo.Reader reader,
			long now) {
		final DownloadInfo info = reader.newDownloadInfo(this, mSystemFacade);
		mDownloads.put(info.mId, info);
		if (Constants.LOGVV) {
			Log.v(Constants.TAG, "processing inserted download " + info.mId);
		}
		return info;
	}
	
	/**
	 * Updates the local copy of the info about a download.
	 */
	private void updateDownload(DownloadInfo.Reader reader, DownloadInfo info,
			long now) {
		reader.updateFromDatabase(info);
		if (Constants.LOGVV) {
			Log.v(Constants.TAG, "processing updated download " + info.mId
					+ ", status: " + info.mStatus);
		}
	}

	/**
	 * Removes the local copy of the info about a download.
	 */
	private void deleteDownloadLocked(long id) {
		DownloadInfo info = mDownloads.get(id);
		if (info.mStatus == Downloads.STATUS_RUNNING) {
			info.mStatus = Downloads.STATUS_CANCELED;
		}
		if (info.mDestination != Downloads.DESTINATION_EXTERNAL
				&& info.mFileName != null) {
			new File(info.mFileName).delete();
		}
		mDownloads.remove(info.mId);
	}
}
