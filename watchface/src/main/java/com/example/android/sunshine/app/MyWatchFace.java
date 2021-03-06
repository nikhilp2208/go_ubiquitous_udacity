/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    public final String LOG_TAG = MyWatchFace.class.getSimpleName();

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    static final String KEY_PATH = "/weather_info";
    static final String KEY_WEATHER_ID = "KEY_WEATHER_ID";
    static final String KEY_MIN_TEMP = "KEY_MIN_TEMP";
    static final String KEY_MAX_TEMP = "KEY_MAX_TEMP";

    private Bitmap mWeatherIcon;
    private String mMaxTemp;
    private String mMinTemp;

    Date mDate;
    DateFormat mDateFormat;

    private GoogleApiClient mGoogleApiClient;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mHourPaint;
        Paint mMinPaint;
        Paint mDatePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;

        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mTimeYOffset;
        float mDateYOffset;
        float mLineSeparatorYOffset;
        float mWeatherIconYOffset;
        float mWeatherYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mLineSeparatorYOffset = resources.getDimension(R.dimen.digital_line_separator_y_offset);
            mWeatherIconYOffset = resources.getDimension(R.dimen.digital_weather_icon_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.digital_weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.primary_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.primary_text));
            mHourPaint = createTextPaint(resources.getColor(R.color.primary_text),BOLD_TYPEFACE);
            mMinPaint = createTextPaint(resources.getColor(R.color.primary_text));
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.primary_text));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.primary_text));

            mWeatherIcon = BitmapFactory.decodeResource(getResources(),R.drawable.art_clear);
            mCalendar = Calendar.getInstance();
            mDate = new Date();
            mDateFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
            mMinTemp = getString(R.string.default_temp);
            mMaxTemp = getString(R.string.default_temp);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            return createTextPaint(textColor,NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            mXOffset = resources.getDimension(R.dimen.digital_x_offset);
            float textSize = resources.getDimension(R.dimen.digital_text_size);

            float dateTextSize = resources.getDimension(R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(R.dimen.digital_temp_text_size);

            mHourPaint.setTextSize(textSize);
            mMinPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
            mTextPaint.setTextSize(textSize);
            mMaxTempPaint.setTextSize(tempTextSize);
            mMinTempPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);


            int hour = mCalendar.get(Calendar.HOUR_OF_DAY);
            int minute = mCalendar.get(Calendar.MINUTE);

            String hourText = String.format("%02d:", hour);
            String minuteText = String.format("%02d", minute);

            float centerX = bounds.centerX();
            float hourSize = mHourPaint.measureText(hourText);
            float minuteSize = mMinPaint.measureText(minuteText);
            float hourXOffset = centerX - (hourSize + minuteSize)/2;
            float minuteXOffset = centerX + (hourSize - minuteSize)/2;

            canvas.drawText(hourText, hourXOffset, mTimeYOffset, mHourPaint);
            canvas.drawText(minuteText, minuteXOffset, mTimeYOffset, mMinPaint);


            String dateString = mDateFormat.format(mDate);
            canvas.drawText(dateString, centerX - mDatePaint.measureText(dateString)/2, mDateYOffset, mDatePaint);

            canvas.drawLine(bounds.centerX() - 25, mLineSeparatorYOffset, bounds.centerX() + 25, mLineSeparatorYOffset, mDatePaint);

            Bitmap resizedBitmap = Bitmap.createScaledBitmap(mWeatherIcon, 40, 40, true);
            String maxTempString = mMaxTemp;
            String minTempString = mMinTemp;
            float maxTempMeasureText = mMaxTempPaint.measureText(maxTempString);
            float maxTempXPosition = centerX - mMaxTempPaint.measureText(maxTempString) / 2;
            float minTempXPosition = maxTempXPosition + maxTempMeasureText + 10;
            if (!isInAmbientMode()) {
                float iconXPosition = maxTempXPosition - (resizedBitmap.getWidth() + 10);
                canvas.drawBitmap(resizedBitmap, iconXPosition, mWeatherIconYOffset, new Paint());
            }
            canvas.drawText(maxTempString, maxTempXPosition, mWeatherYOffset, mMaxTempPaint);
            canvas.drawText(minTempString, minTempXPosition, mWeatherYOffset, mMinTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
                Log.d(LOG_TAG,"onConnected");
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG,"received data change");
            for (DataEvent dataEvent: dataEventBuffer) {
                if(dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if(dataItem.getUri().getPath().equals(KEY_PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                        mWeatherIcon = BitmapFactory.decodeResource(getResources(),
                                Utils.getArtResourceForWeatherCondition(weatherId));
                        mMaxTemp = dataMap.getString(KEY_MAX_TEMP);
                        mMinTemp = dataMap.getString(KEY_MIN_TEMP);
                        Log.d(LOG_TAG,"max temp: " + mMaxTemp + ", min temp:" + mMinTemp);
                        invalidate();
                    }
                }

            }
        }
    }
}
