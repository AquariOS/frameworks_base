/*
 * Copyright (C) 2018-2018 crDroid Android Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.aqua.statusbarweather;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.omni.OmniJawsClient;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.tuner.TunerService;

import java.util.Arrays;

public class StatusBarWeatherRight extends TextView implements
        OmniJawsClient.OmniJawsObserver, TunerService.Tunable, DarkIconDispatcher.DarkReceiver {

    private static final String TAG = StatusBarWeatherRight.class.getSimpleName();

    private Context mContext;

    private boolean mAttached;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private int mStatusBarWeatherEnabled;
    private int mWeatherTempStyle;
    private int mWeatherTempColor;
    private int mWeatherTempSize;
    private int mWeatherTempFontStyle;
    private int mTintColor = Color.WHITE;

    // Weather temperature
    public static final int FONT_NORMAL = 0;
    public static final int FONT_ITALIC = 1;
    public static final int FONT_BOLD = 2;
    public static final int FONT_BOLD_ITALIC = 3;
    public static final int FONT_LIGHT = 4;
    public static final int FONT_LIGHT_ITALIC = 5;
    public static final int FONT_THIN = 6;
    public static final int FONT_THIN_ITALIC = 7;
    public static final int FONT_CONDENSED = 8;
    public static final int FONT_CONDENSED_ITALIC = 9;
    public static final int FONT_CONDENSED_LIGHT = 10;
    public static final int FONT_CONDENSED_LIGHT_ITALIC = 11;
    public static final int FONT_CONDENSED_BOLD = 12;
    public static final int FONT_CONDENSED_BOLD_ITALIC = 13;
    public static final int FONT_MEDIUM = 14;
    public static final int FONT_MEDIUM_ITALIC = 15;
    public static final int FONT_BLACK = 16;
    public static final int FONT_BLACK_ITALIC = 17;
    public static final int FONT_DANCINGSCRIPT = 18;
    public static final int FONT_DANCINGSCRIPT_BOLD = 19;
    public static final int FONT_COMINGSOON = 20;
    public static final int FONT_NOTOSERIF = 21;
    public static final int FONT_NOTOSERIF_ITALIC = 22;
    public static final int FONT_NOTOSERIF_BOLD = 23;
    public static final int FONT_NOTOSERIF_BOLD_ITALIC = 24;

    private static final String STATUS_BAR_SHOW_WEATHER_TEMP =
            "system:" + Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP;
    private static final String STATUS_BAR_WEATHER_TEMP_STYLE =
            "system:" + Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE;
    private static final String STATUS_BAR_WEATHER_SIZE =
            "system:" + Settings.System.STATUS_BAR_WEATHER_SIZE;
    private static final String STATUS_BAR_WEATHER_FONT_STYLE =
            "system:" + Settings.System.STATUS_BAR_WEATHER_FONT_STYLE;
    private static final String STATUS_BAR_WEATHER_COLOR =
            "system:" + Settings.System.STATUS_BAR_WEATHER_COLOR;

    public StatusBarWeatherRight(Context context) {
        this(context, null);
    }

    public StatusBarWeatherRight(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeatherRight(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mWeatherClient = new OmniJawsClient(mContext);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached)
            return;

        mAttached = true;
        mWeatherClient.addObserver(this);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        Dependency.get(TunerService.class).addTunable(this,
                STATUS_BAR_SHOW_WEATHER_TEMP,
                STATUS_BAR_WEATHER_TEMP_STYLE,
                STATUS_BAR_WEATHER_SIZE,
                STATUS_BAR_WEATHER_FONT_STYLE,
                STATUS_BAR_WEATHER_COLOR);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached)
            return;

        mAttached = false;
        Dependency.get(TunerService.class).removeTunable(this);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(area, this, tint);
        if (mWeatherTempColor == 0xFFFFFFFF)
            updateattributes();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
       switch (key) {
            case STATUS_BAR_SHOW_WEATHER_TEMP:
                mStatusBarWeatherEnabled =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                queryAndUpdateWeather();
                break;
            case STATUS_BAR_WEATHER_TEMP_STYLE:
                mWeatherTempStyle =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                queryAndUpdateWeather();
                break;
            case STATUS_BAR_WEATHER_SIZE:
                mWeatherTempSize =
                        newValue == null ? 14 : Integer.parseInt(newValue);
                updateattributes();
                break;
            case STATUS_BAR_WEATHER_FONT_STYLE:
                mWeatherTempFontStyle =
                        newValue == null ? FONT_NORMAL : Integer.parseInt(newValue);
                updateattributes();
                break;
            case STATUS_BAR_WEATHER_COLOR:
                mWeatherTempColor =
                        newValue == null ? 0xFFFFFFFF : Integer.parseInt(newValue);
                updateattributes();
                break;
            default:
                break;
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (mWeatherData == null) {
            setText(null);
            setVisibility(View.GONE);
        } else {
            updateattributes();
        }
    }

    private boolean allowVisibility() {
        if (mWeatherTempStyle == 0 || mStatusBarWeatherEnabled == 0 ||
                mStatusBarWeatherEnabled == 5 || !mWeatherClient.isOmniJawsEnabled()) {
            setText(null);
            setVisibility(View.GONE);
            return false;
        }
        return true;
    }

    private void queryAndUpdateWeather() {
        if (!allowVisibility()) {
            return;
        }

        updateattributes();
        try {
            mWeatherClient.queryWeather();
            mWeatherData = mWeatherClient.getWeatherInfo();
            if (mWeatherData == null) {
                return;
            }
            if (mStatusBarWeatherEnabled == 2 || mStatusBarWeatherEnabled == 4) {
                setText(mWeatherData.temp);
            } else {
                setText(mWeatherData.temp + mWeatherData.tempUnits);
            }
            setVisibility(View.VISIBLE);
        } catch (Exception e) {
            // Do nothing
        }
    }

    private void updateattributes() {
        if (!allowVisibility()) {
            return;
        }
        try {
            if (mWeatherTempColor == 0xFFFFFFFF) {
                setTextColor(mTintColor);
            } else {
                setTextColor(mWeatherTempColor);
            }
            setTextSize(mWeatherTempSize);
            switch (mWeatherTempFontStyle) {
                case FONT_NORMAL:
                default:
                    setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    break;
                case FONT_ITALIC:
                    setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                    break;
                case FONT_BOLD:
                    setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                    break;
                case FONT_BOLD_ITALIC:
                    setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                    break;
                case FONT_LIGHT:
                    setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                    break;
                case FONT_LIGHT_ITALIC:
                    setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                    break;
                case FONT_THIN:
                    setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                    break;
                case FONT_THIN_ITALIC:
                    setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                    break;
                case FONT_CONDENSED:
                    setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                    break;
                case FONT_CONDENSED_ITALIC:
                    setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                    break;
                case FONT_CONDENSED_LIGHT:
                    setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                    break;
                case FONT_CONDENSED_LIGHT_ITALIC:
                    setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                    break;
                case FONT_CONDENSED_BOLD:
                    setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                    break;
                case FONT_CONDENSED_BOLD_ITALIC:
                    setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                    break;
                case FONT_MEDIUM:
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    break;
                case FONT_MEDIUM_ITALIC:
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                    break;
                case FONT_BLACK:
                    setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                    break;
                case FONT_BLACK_ITALIC:
                    setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                    break;
                case FONT_DANCINGSCRIPT:
                    setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                    break;
                case FONT_DANCINGSCRIPT_BOLD:
                    setTypeface(Typeface.create("cursive", Typeface.BOLD));
                    break;
                case FONT_COMINGSOON:
                    setTypeface(Typeface.create("casual", Typeface.NORMAL));
                    break;
                case FONT_NOTOSERIF:
                    setTypeface(Typeface.create("serif", Typeface.NORMAL));
                    break;
                case FONT_NOTOSERIF_ITALIC:
                    setTypeface(Typeface.create("serif", Typeface.ITALIC));
                    break;
                case FONT_NOTOSERIF_BOLD:
                    setTypeface(Typeface.create("serif", Typeface.BOLD));
                    break;
                case FONT_NOTOSERIF_BOLD_ITALIC:
                    setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                    break;
            }
        } catch (Exception e) {
            // Do nothing
        }
    }
}
