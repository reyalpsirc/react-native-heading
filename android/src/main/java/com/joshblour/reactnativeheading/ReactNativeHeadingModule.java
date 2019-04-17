package com.joshblour.reactnativeheading;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;


import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;


public class ReactNativeHeadingModule extends ReactContextBaseJavaModule implements SensorEventListener {


    private static Context mApplicationContext;
    private SensorManager mSensorManager;
    private WindowManager windowManager;
    // Filtering coefficient 0 < ALPHA < 1
    private static final float ALPHA = 0.45f;

    // Controls the compass update rate in milliseconds
    private static final int COMPASS_UPDATE_RATE_MS = 500;


    // Not all devices have a compassSensor
    private Sensor compassSensor;
    private Sensor gravitySensor;
    private Sensor magneticFieldSensor;

    private float[] truncatedRotationVectorValue = new float[4];
    private float[] rotationMatrix = new float[9];
    private float[] rotationVectorValue;
    private float lastHeading;
    private int lastAccuracySensorStatus;

    private long compassUpdateNextTimestamp;
    private float[] gravityValues = new float[3];
    private float[] magneticValues = new float[3];
    private int mCurrentDegree = 0;
    private int mFilter = 5;
    private float previousCompassBearing = -1f;

    public ReactNativeHeadingModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mApplicationContext = reactContext.getApplicationContext();
    }

    @Override
    public String getName() {
        return "ReactNativeHeading";
    }

    @ReactMethod
    public void start(int filter, Promise promise) {

        if (windowManager == null) {
            windowManager = (WindowManager) mApplicationContext.getSystemService(Context.WINDOW_SERVICE);
        }
        if (mSensorManager == null) {
            mSensorManager = (SensorManager) mApplicationContext.getSystemService(Context.SENSOR_SERVICE);
        }

        if (compassSensor == null) {
            compassSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (compassSensor == null) {
            if (isGyroscopeAvailable()) {
                compassSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
            } else {
                gravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                magneticFieldSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            }
        }
        mFilter = filter;
        boolean started = this.registerSensorListeners();
        promise.resolve(started);
    }

    @ReactMethod
    public void stop() {
        this.unregisterSensorListeners();
    }

    private boolean isGyroscopeAvailable() {
        return mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    private boolean registerSensorListeners() {
        if (isCompassSensorAvailable()) {
            // Does nothing if the sensors already registered.
            return mSensorManager.registerListener(this, compassSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            boolean started = mSensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_UI);
            if (started) started = mSensorManager.registerListener(this, magneticFieldSensor, SensorManager.SENSOR_DELAY_UI);
            return started;
        }
    }

    private void unregisterSensorListeners() {
        if (isCompassSensorAvailable()) {
            mSensorManager.unregisterListener(this, compassSensor);
        } else {
            mSensorManager.unregisterListener(this, gravitySensor);
            mSensorManager.unregisterListener(this, magneticFieldSensor);
        }
    }

    private boolean isCompassSensorAvailable() {
        return compassSensor != null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // check when the last time the compass was updated, return if too soon.
        long currentTime = SystemClock.elapsedRealtime();
        if (currentTime < compassUpdateNextTimestamp) {
            return;
        }
        if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            return;
        }
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            rotationVectorValue = getRotationVectorFromSensorEvent(event);
            updateOrientation();

            // Update the compassUpdateNextTimestamp
            compassUpdateNextTimestamp = currentTime + COMPASS_UPDATE_RATE_MS;
        } else if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
            notifyNewAngleIfNeeded((event.values[0] + 360) % 360);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            gravityValues = lowPassFilter(getRotationVectorFromSensorEvent(event), gravityValues);
            updateOrientation();
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticValues = lowPassFilter(getRotationVectorFromSensorEvent(event), magneticValues);
            updateOrientation();
        }
    }

    private void updateOrientation() {
        if (rotationVectorValue != null) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValue);
        } else {
            // Get rotation matrix given the gravity and geomagnetic matrices
            SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magneticValues);
        }

        final int worldAxisForDeviceAxisX;
        final int worldAxisForDeviceAxisY;

        // Remap the axes as if the device screen was the instrument panel,
        // and adjust the rotation matrix for the device orientation.
        switch (windowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                worldAxisForDeviceAxisX = SensorManager.AXIS_Z;
                worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_X;
                worldAxisForDeviceAxisY = SensorManager.AXIS_MINUS_Z;
                break;
            case Surface.ROTATION_270:
                worldAxisForDeviceAxisX = SensorManager.AXIS_MINUS_Z;
                worldAxisForDeviceAxisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                worldAxisForDeviceAxisX = SensorManager.AXIS_X;
                worldAxisForDeviceAxisY = SensorManager.AXIS_Z;
                break;
        }

        float[] adjustedRotationMatrix = new float[9];
        SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisForDeviceAxisX,
                worldAxisForDeviceAxisY, adjustedRotationMatrix);

        // Transform rotation matrix into azimuth/pitch/roll
        float[] orientation = new float[3];
        SensorManager.getOrientation(adjustedRotationMatrix, orientation);

        // The x-axis is all we care about here.
        notifyNewAngleIfNeeded((float)Math.toDegrees(orientation[0]));
    }

    /**
     * Helper function, that filters newValues, considering previous values
     *
     * @param newValues      array of float, that contains new data
     * @param smoothedValues array of float, that contains previous state
     * @return float filtered array of float
     */
    private float[] lowPassFilter(float[] newValues, float[] smoothedValues) {
        if (smoothedValues == null) {
            return newValues;
        }
        for (int i = 0; i < newValues.length; i++) {
            smoothedValues[i] = smoothedValues[i] + ALPHA * (newValues[i] - smoothedValues[i]);
        }
        return smoothedValues;
    }

    /**
     * Pulls out the rotation vector from a SensorEvent, with a maximum length
     * vector of four elements to avoid potential compatibility issues.
     *
     * @param event the sensor event
     * @return the events rotation vector, potentially truncated
     */
    private float[] getRotationVectorFromSensorEvent(SensorEvent event) {
        if (event.values.length > 4) {
            // On some Samsung devices SensorManager.getRotationMatrixFromVector
            // appears to throw an exception if rotation vector has length > 4.
            // For the purposes of this class the first 4 values of the
            // rotation vector are sufficient (see crbug.com/335298 for details).
            // Only affects Android 4.3
            System.arraycopy(event.values, 0, truncatedRotationVectorValue, 0, 4);
            return truncatedRotationVectorValue;
        } else {
            return event.values;
        }
    }

    public float shortestRotation(float heading, float previousHeading) {
        double diff = previousHeading - heading;
        if (diff > 180.0f) {
            heading += 360.0f;
        } else if (diff < -180.0f) {
            heading -= 360.f;
        }
        return heading;
    }

    private void notifyNewAngleIfNeeded(float angle) {
        if (previousCompassBearing < 0) {
            previousCompassBearing = angle;
        }
        float normalizedBearing =
                shortestRotation(angle, previousCompassBearing) + 360;
        previousCompassBearing = angle;
        int curAngle = Math.round(normalizedBearing);
        if (Math.abs(mCurrentDegree - curAngle) < mFilter) {
            return;
        }
        mCurrentDegree = curAngle;
        WritableMap params = Arguments.createMap();
        params.putInt("heading", Math.round(mCurrentDegree));
        params.putInt("accuracy", lastAccuracySensorStatus);


        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("headingUpdated", params);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (lastAccuracySensorStatus != accuracy) {
            lastAccuracySensorStatus = accuracy;
        }
    }
}
