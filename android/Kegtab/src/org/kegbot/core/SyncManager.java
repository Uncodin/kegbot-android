/*
 * Copyright 2012 Mike Wakerly <opensource@hoho.com>.
 *
 * This file is part of the Kegtab package from the Kegbot project. For
 * more information on Kegtab or Kegbot, see <http://kegbot.org/>.
 *
 * Kegtab is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 2.
 *
 * Kegtab is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with Kegtab. If not, see <http://www.gnu.org/licenses/>.
 */
package org.kegbot.core;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.codehaus.jackson.JsonNode;
import org.kegbot.api.KegbotApi;
import org.kegbot.api.KegbotApiException;
import org.kegbot.api.KegbotApiNotFoundError;
import org.kegbot.app.event.ConnectivityChangedEvent;
import org.kegbot.app.event.CurrentSessionChangedEvent;
import org.kegbot.app.event.DrinkPostedEvent;
import org.kegbot.app.event.SoundEventListUpdateEvent;
import org.kegbot.app.event.SystemEventListUpdateEvent;
import org.kegbot.app.event.TapListUpdateEvent;
import org.kegbot.app.storage.LocalDbHelper;
import org.kegbot.proto.Api.RecordDrinkRequest;
import org.kegbot.proto.Api.RecordTemperatureRequest;
import org.kegbot.proto.Internal.PendingPour;
import org.kegbot.proto.Models.Drink;
import org.kegbot.proto.Models.KegTap;
import org.kegbot.proto.Models.Session;
import org.kegbot.proto.Models.SoundEvent;
import org.kegbot.proto.Models.SystemEvent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;

import com.google.common.collect.Lists;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.squareup.otto.Bus;
import com.squareup.otto.Produce;
import com.squareup.otto.Subscribe;

/**
 * This service manages a connection to a Kegbot backend, using the Kegbot API.
 * It implements the {@link KegbotApi} interface, potentially employing caching.
 */
public class SyncManager extends BackgroundManager {

  private static String TAG = SyncManager.class.getSimpleName();

  private final KegbotApi mApi;
  private final Context mContext;

  private List<KegTap> mLastKegTapList = Lists.newArrayList();
  private List<SystemEvent> mLastSystemEventList = Lists.newArrayList();
  private List<SoundEvent> mLastSoundEventList = Lists.newArrayList();
  @Nullable private Session mLastSession = null;
  @Nullable private JsonNode mLastSessionStats = null;

  private SQLiteOpenHelper mLocalDbHelper;

  private boolean mRunning = true;
  private boolean mSyncImmediate = true;

  private long mNextSyncTime = Long.MIN_VALUE;

  private static final long SYNC_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
  private static final long SYNC_INTERVAL_AGGRESSIVE_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private ExecutorService mApiExecutorService;

  public static Comparator<SystemEvent> EVENTS_DESCENDING = new Comparator<SystemEvent>() {
    @Override
    public int compare(SystemEvent object1, SystemEvent object2) {
      try {
        final long time1 = org.kegbot.app.util.DateUtils.dateFromIso8601String(object1.getTime());
        final long time2 = org.kegbot.app.util.DateUtils.dateFromIso8601String(object2.getTime());
        return Long.valueOf(time2).compareTo(Long.valueOf(time1));
      } catch (IllegalArgumentException e) {
        Log.wtf(TAG, "Error parsing times", e);
        return 0;
      }
    }
  };

  public static Comparator<SoundEvent> SOUND_EVENT_COMPARATOR = new Comparator<SoundEvent>() {
    @Override
    public int compare(SoundEvent object1, SoundEvent object2) {
      int cmp = object1.getEventName().compareTo(object2.getEventName());
      if (cmp == 0) {
        cmp = object1.getEventPredicate().compareTo(object2.getEventPredicate());
      }
      return cmp;
    }
  };

  public SyncManager(Bus bus, Context context, KegbotApi api) {
    super(bus);
    mApi = api;
    mContext = context;
  }

  @Override
  public synchronized void start() {
    Log.d(TAG, "Opening local database");
    mRunning = true;
    mSyncImmediate = true;
    mNextSyncTime = Long.MIN_VALUE;
    mApiExecutorService = Executors.newSingleThreadExecutor();
    mLocalDbHelper = new LocalDbHelper(mContext);
    mRunning = true;
    getBus().register(this);
    super.start();
  }

  @Override
  public synchronized void stop() {
    mRunning = false;
    getBus().unregister(this);
    mLastKegTapList.clear();
    mApiExecutorService.shutdown();
    super.stop();
  }

  /** Schedules a drink to be recorded asynchronously. */
  public synchronized void recordDrinkAsync(final Flow flow) {
    if (!mRunning) {
      Log.e(TAG, "Record drink request while not running.");
      return;
    }
    final RecordDrinkRequest request = getRequestForFlow(flow);
    final PendingPour pour = PendingPour.newBuilder()
        .setDrinkRequest(request)
        .addAllImages(flow.getImages())
        .build();

    postDeferredPoursAsync();
    mApiExecutorService.submit(new Runnable() {
      @Override
      public void run() {
        try {
          postPour(pour);
        } catch (KegbotApiException e) {
          Log.d(TAG, "Caught exception posting pour: " + e);
          deferPostPour(pour);
        } catch (Exception e) {
          Log.w(TAG, "Error posting pour: " + e, e);
        }
      }
    });
  }

  private void postDeferredPoursAsync() {
    mApiExecutorService.submit(new Runnable() {
      @Override
      public void run() {
        postDeferredPours();
      }
    });
  }

  /**
   * Schedules a temperature reading to be recorded asynchronously.
   *
   * @param request
   */
  public synchronized void recordTemperatureAsync(final RecordTemperatureRequest request) {
    if (!mRunning) {
      Log.e(TAG, "Record thermo request while not running.");
      return;
    }
    mApiExecutorService.submit(new Runnable() {
      @Override
      public void run() {
        try {
          postThermoLog(request);
        } catch (KegbotApiException e) {
          // Don't both retrying.
          Log.w(TAG, String.format("Error posting thermo, dropping: %s", e));
        }
      }
    });
  }

  public synchronized void requestSync() {
    Log.d(TAG, "Immediate sync requested.");
    mSyncImmediate = true;
  }

  @Produce
  public TapListUpdateEvent produceTapList() {
    return new TapListUpdateEvent(Lists.newArrayList(mLastKegTapList));
  }

  @Produce
  public SystemEventListUpdateEvent produceSystemEvents() {
    return new SystemEventListUpdateEvent(Lists.newArrayList(mLastSystemEventList));
  }

  @Produce
  public CurrentSessionChangedEvent produceCurrentSession() {
    return new CurrentSessionChangedEvent(mLastSession, mLastSessionStats);
  }

  @Produce
  public SoundEventListUpdateEvent produceSoundEvents() {
    return new SoundEventListUpdateEvent(mLastSoundEventList);
  }

  @Subscribe
  public void handleConnectivityChangedEvent(ConnectivityChangedEvent event) {
    if (event.isConnected()) {
      Log.d(TAG, "Connection is up, requesting sync.");
      requestSync();
    } else {
      Log.d(TAG, "Connection is down.");
    }
  }

  @Override
  protected void runInBackground() {
    Log.i(TAG, "Running in background.");

    try {
      while (true) {
        synchronized (this) {
          if (!mRunning) {
            Log.d(TAG, "No longer running, exiting.");
            break;
          }
        }

        long now = SystemClock.elapsedRealtime();
        if (mSyncImmediate == true || now > mNextSyncTime) {
          Log.d(TAG, "Syncing: syncImmediate=" + mSyncImmediate + " mNextSyncTime=" + mNextSyncTime);
          mSyncImmediate = false;

          boolean syncError = true;
          try {
            syncError = syncNow();
          } finally {
            mNextSyncTime = SystemClock.elapsedRealtime() +
                (syncError ? SYNC_INTERVAL_AGGRESSIVE_MILLIS : SYNC_INTERVAL_MILLIS);
          }

        }

        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Log.d(TAG, "Interrupted.");
          Thread.currentThread().interrupt();
          break;
        }
      }
    } catch (Throwable e) {
      Log.wtf(TAG, "Uncaught exception in background.", e);
    }
  }

  private void deferPostPour(PendingPour pour) {
    Log.d(TAG, "Deferring pour: " + pour);
    addSingleRequestToDb(pour);
  }

  /**
   * Synchronously posts a single pour to the remote backend. This method is
   * guaranteed to have succeeded on non-exceptional return.
   */
  private void postPour(final PendingPour pour) throws KegbotApiException {
    final RecordDrinkRequest request = pour.getDrinkRequest();
    Log.d(TAG, ">>> Posting pour: tap=" + request.getTapName() + " ticks=" + request.getTicks());

    if (!isConnected()) {
      throw new KegbotApiException("Not connected.");
    }

    final Drink drink;
    try {
      drink = mApi.recordDrink(request);
    } catch (KegbotApiNotFoundError e) {
      Log.w(TAG, "Tap does not exist, dropping pour.");
      return;
    }

    Log.d(TAG, "<<< Success, drink posted: " + drink);
    postOnMainThread(new DrinkPostedEvent(drink));

    if (pour.getImagesCount() > 0) {
      // TODO(mikey): Single image everywhere.
      final String imagePath = pour.getImagesList().get(0);
      Log.d(TAG, "Drink had image, trying to post it.");
      try {
        if (drink != null) {
          Log.d(TAG, "Uploading image: " + imagePath);
          try {
            mApi.uploadDrinkImage(drink.getId(), imagePath);
          } catch (KegbotApiException e) {
            // Discard image, no retry.
            Log.w(TAG, String.format("Error uploading image %s: %s", imagePath, e));
          }
        }
      } finally {
        for (final String image : pour.getImagesList()) {
          if (new File(image).delete()) {
            Log.d(TAG, "Deleted " + image);
          }
        }
      }
    }
  }

  /**
   * Synchronously posts a single thermo log to the remote backend. This method
   * is guaranteed to have succeeded on non-exceptional return.
   */
  private void postThermoLog(final RecordTemperatureRequest request) throws KegbotApiException {
    Log.d(TAG, ">>> Posting thermo log: tap=" + request.getSensorName() + " value=" + request.getTempC());
    if (!isConnected()) {
      throw new KegbotApiException("Not connected.");
    }
    mApi.recordTemperature(request);
    Log.d(TAG, "<<< Success.");
  }

  /** Posts any queued requests to the api service. */
  private void postDeferredPours() {
    final SQLiteDatabase db = mLocalDbHelper.getWritableDatabase();

    // Fetch most recent entry.
    final Cursor cursor =
      db.query(LocalDbHelper.TABLE_NAME,
          null, null, null, null, null, LocalDbHelper.COLUMN_NAME_ADDED_DATE + " ASC", "1");
    try {
      final int numPending = cursor.getCount();
      if (numPending == 0) {
        return;
      }

      Log.d(TAG, String.format("Processing %s deferred pour%s.",
          Integer.valueOf(numPending), numPending == 1 ? "" : "s"));
      cursor.moveToFirst();

      boolean deleteRow = true;
      try {
        final AbstractMessage record = LocalDbHelper.getCurrentRow(db, cursor);
        if (record instanceof PendingPour) {
          try {
            postPour((PendingPour) record);
          } catch (KegbotApiException e) {
            // Try later.
            deleteRow = false;
          }
          // Sync taps, etc, on new drink.
          mSyncImmediate = true;
        }
      } catch (InvalidProtocolBufferException e) {
        Log.w(TAG, "Error processing column: " + e);
      }

      if (deleteRow) {
        final int deleteResult = LocalDbHelper.deleteCurrentRow(db, cursor);
        Log.d(TAG, "Deleted row, result = " + deleteResult);
      }
    } finally {
      cursor.close();
      db.close();
    }
  }

  private boolean addSingleRequestToDb(AbstractMessage message) {
    Log.d(TAG, "Adding request to db!");
    final String type;
    if (message instanceof PendingPour) {
      type = "pour";
    } else if (message instanceof RecordTemperatureRequest) {
      type = "thermo";
    } else {
      Log.w(TAG, "Unknown record type; dropping.");
      return false;
    }
    Log.d(TAG, "Request is a " + type);

    final ContentValues values = new ContentValues();
    values.put(LocalDbHelper.COLUMN_NAME_TYPE, type);
    values.put(LocalDbHelper.COLUMN_NAME_RECORD, message.toByteArray());

    boolean inserted = false;
    final SQLiteDatabase db = mLocalDbHelper.getWritableDatabase();
    try {
      db.insert(LocalDbHelper.TABLE_NAME, null, values);
      inserted = true;
    } finally {
      db.close();
    }
    return inserted;
  }

  private boolean isConnected() {
    final ConnectivityManager cm =
        (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    final NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
    if (activeNetwork == null || !activeNetwork.isConnected()) {
      return false;
    }
    return true;
  }

  private boolean syncNow() {
    boolean error = false;

    if (!isConnected()) {
      error = true;
      Log.d(TAG, "Network not connected.");
      return error;
    }

    postDeferredPoursAsync();

    // Taps.
    try {
      List<KegTap> newTaps = mApi.getAllTaps();
      if (!newTaps.equals(mLastKegTapList)) {
        mLastKegTapList = newTaps;
        postOnMainThread(new TapListUpdateEvent(newTaps));
      }
    } catch (KegbotApiException e) {
      Log.w(TAG, "Error syncing taps: " + e);
      error = true;
    }

    // System events.
    SystemEvent lastEvent = null;
    if (!mLastSystemEventList.isEmpty()) {
      lastEvent = mLastSystemEventList.get(0);
    }

    try {
      List<SystemEvent> newEvents;
      if (lastEvent != null) {
        newEvents = mApi.getRecentEvents(lastEvent.getId());
      } else {
        newEvents = mApi.getRecentEvents();
      }
      Collections.sort(newEvents, EVENTS_DESCENDING);

      if (!newEvents.isEmpty()) {
        mLastSystemEventList.clear();
        mLastSystemEventList.addAll(newEvents);
        postOnMainThread(new SystemEventListUpdateEvent(mLastSystemEventList));
      }
    } catch (KegbotApiException e) {
      Log.w(TAG, "Error syncing events: " + e);
      error = true;
    }

    // Current session
    try {
      Session currentSession = mApi.getCurrentSession();
      if ((currentSession == null && mLastSession != null) ||
          (mLastSession == null && currentSession != null) ||
          (currentSession != null && !currentSession.equals(mLastSession))) {
        JsonNode stats = null;
        if (currentSession != null) {
          stats = mApi.getSessionStats(currentSession.getId());
        }
        mLastSession = currentSession;
        mLastSessionStats = stats;
        postOnMainThread(new CurrentSessionChangedEvent(currentSession, stats));
      }
    } catch (KegbotApiException e) {
      Log.w(TAG, "Error syncing current session: " + e);
      error = true;
    }

    // Sound events
    try {
      List<SoundEvent> events = mApi.getAllSoundEvents();
      Collections.sort(events, SOUND_EVENT_COMPARATOR);
      if (!events.equals(mLastSoundEventList)) {
        mLastSoundEventList.clear();
        mLastSoundEventList.addAll(events);
        postOnMainThread(new SoundEventListUpdateEvent(mLastSoundEventList));
      }
    } catch (KegbotApiException e) {
      Log.w(TAG, "Error syncing sound events: " + e);
      error = true;
    }

    return error;
  }

  private static RecordDrinkRequest getRequestForFlow(final Flow ended) {
    return RecordDrinkRequest.newBuilder()
        .setTapName(ended.getTap().getMeterName())
        .setTicks(ended.getTicks())
        .setVolumeMl((float) ended.getVolumeMl())
        .setUsername(ended.getUsername())
        .setSecondsAgo(0)
        .setDurationSeconds((int) (ended.getDurationMs() / 1000.0))
        .setSpilled(false)
        .setShout(ended.getShout())
        .setTickTimeSeries(ended.getTickTimeSeries().asString())
        .buildPartial();
  }

}
