/* Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import android.R.integer;
import android.app.ActionBar;
import android.content.Intent;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;

public class WifiCallingSettings extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final String TAG = WifiCallingSettings.class.getSimpleName();

    private static final String WIFI_CALLING_KEY = "wifi_calling";
    private static final String WIFI_CALLING_PREFERENCE_KEY = "wifi_calling_preferrence";
    private static final String KEY_WIFI_CALLING_WIZARD = "wifi_calling_wizard";
    private static final String KEY_WIFI_CALLING_PREFERRED_SCREEN = "wifi_calling_prefence";
    // Below is the prefence key as the same as the CheckBoxPreference title.
    private static final String KEY_WIFI_CALLING_PREFERRED = "Wi-Fi Preferred";
    private static final String KEY_CELLULAR_NETWORK_PREFERRED = "Cellular Network Preferred";
    private static final String KEY_NEVER_USE_CELLULAR_NETWORK_PREFERRED = "Never use Cellular Network";
    private static final String KEY_WIFI_CALLING_CONNECTION_PREFERENCE = "wifi_calling_connection_pref";

    private static final int WIFI_PREF_NONE = 0;
    private static final int WIFI_PREFERRED = 1;
    private static final int WIFI_ONLY = 2;
    private static final int CELLULAR_PREFERRED = 3;
    private static final int CELLULAR_ONLY = 4;
    private static int mStatus = 1;


    private SwitchPreference mWifiCallingSetting;
    private ListPreference mWifiCallingPreference;
    private Preference mWifiCallingHelp;
    private Preference mWifiCallingPreCarrier;
    private Preference mWifiCallingConnectPre;
    private PreferenceCategory mPrefCate;
    private ImsConfig mImsConfig;
    private Switch mSwitch;
    private int mSelection = 0;
    private ArrayList<CheckBoxPreference> mCheckboxPref = new ArrayList<CheckBoxPreference>();
    private Map<String, Integer> mPrefenceIndex = new HashMap<String, Integer>();

    @Override
    protected void onCreate(Bundle icicle) {
        setTheme(R.style.Theme_Material_Settings);
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.wifi_call_setting);
        PreferenceScreen screen = getPreferenceScreen();

        mWifiCallingSetting = (SwitchPreference)screen.findPreference(WIFI_CALLING_KEY);
        mWifiCallingSetting.setOnPreferenceChangeListener(this);

        mWifiCallingPreference = (ListPreference)screen.findPreference(WIFI_CALLING_PREFERENCE_KEY);
        mWifiCallingPreference.setOnPreferenceChangeListener(this);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        try {
            ImsManager imsManager = ImsManager.getInstance(getBaseContext(),
                    SubscriptionManager.getDefaultVoiceSubId());
            mImsConfig = imsManager.getConfigInterface();
        } catch (ImsException e) {
            mImsConfig = null;
            Log.e(TAG, "ImsService is not running");
        }
        updatePrefence();
    }

    private boolean isCarriers(){
        return getBaseContext().getResources().getBoolean(
                com.android.internal.R.bool.config_regional_wifi_calling_menu_enable);
    }

    private void updatePrefence(){
        final PreferenceScreen screen = getPreferenceScreen();
        mWifiCallingPreCarrier = screen
                .findPreference(WIFI_CALLING_PREFERENCE_KEY);
        mWifiCallingHelp = screen.findPreference("wifi_calling_tutorial");
        mWifiCallingConnectPre = screen
                .findPreference(KEY_WIFI_CALLING_CONNECTION_PREFERENCE);
        mWifiCallingConnectPre.setOnPreferenceClickListener(this);
        if (!isCarriers()) {
            if (mWifiCallingPreCarrier != null) {
                screen.removePreference(mWifiCallingPreCarrier);
            }
            if (mWifiCallingHelp != null) {
                screen.removePreference(mWifiCallingHelp);
            }
            if (mWifiCallingConnectPre != null) {
                screen.removePreference(screen);
            }
            return;
        } else {
            if (mWifiCallingSetting != null) {
                screen.removePreference(mWifiCallingSetting);
            }
            if (mWifiCallingPreference != null) {
                screen.removePreference(mWifiCallingPreference);
            }
            mSwitch = new Switch(this);
            mSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {

                @Override
                public void onCheckedChanged(CompoundButton buttonView,
                        boolean isChecked) {
                    Log.i(TAG, "onCheckedChanged isChecked : " + isChecked);
                    setWifiCallingPreference(
                            isChecked ? ImsConfig.WifiCallingValueConstants.ON
                                    : ImsConfig.WifiCallingValueConstants.OFF,
                            mSelection);
                    mPrefCate.setEnabled(isChecked);
                }
            });
            ActionBar actionBar = getActionBar();
            if (actionBar != null && mSwitch != null) {
                actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
                        ActionBar.DISPLAY_SHOW_CUSTOM);
                actionBar.setCustomView(mSwitch, new ActionBar.LayoutParams(
                        ActionBar.LayoutParams.WRAP_CONTENT,
                        ActionBar.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL | Gravity.RIGHT));
            }
            mPrefCate = (PreferenceCategory) findPreference(KEY_WIFI_CALLING_PREFERRED_SCREEN);
            int current = 0;
            String[] titleArray = getBaseContext().getResources().getStringArray(
                    R.array.wifi_call_preferences_entries_title);
            String[] summaryArray = getBaseContext().getResources().getStringArray(
                    R.array.wifi_call_preferences_entries_summary);
            String[] entriesArray = getBaseContext().getResources().getStringArray(
                    R.array.wifi_call_preferences_entries);
            for (int i=0;i<titleArray.length;i++) {
                CheckBoxPreference pref = new CheckBoxPreference(this);
                pref.setKey(titleArray[i]);
                pref.setOnPreferenceClickListener(this);
                pref.setChecked(i == current ? true : false);
                pref.setTitle(titleArray[i]);
                pref.setSummary(summaryArray[i]);
                mPrefCate.addPreference(pref);
                mCheckboxPref.add(pref);
                mPrefenceIndex.put(titleArray[i], Integer.parseInt(entriesArray[i]));
                if (pref.isChecked()) mSelection = Integer.parseInt(entriesArray[i]);
            }
            screen.removePreference(mPrefCate);
            if (mStatus == 2) {
                changeToPreference();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isWifiCallingPreferenceSupported() && mWifiCallingPreference != null) {
            getPreferenceScreen().removePreference(mWifiCallingPreference);
            mWifiCallingPreference = null;
        }
        getWifiCallingPreference();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mWifiCallingSetting) {
            mWifiCallingSetting.setChecked(!mWifiCallingSetting.isChecked());
            final int status =  mWifiCallingSetting.isChecked() ?
                    ImsConfig.WifiCallingValueConstants.ON :
                    ImsConfig.WifiCallingValueConstants.OFF;
            int wifiPreference;
            if (mWifiCallingPreference != null) {
                wifiPreference = mWifiCallingPreference.getValue() == null ?
                        ImsConfig.WifiCallingPreference.WIFI_PREF_NONE :
                        Integer.valueOf(mWifiCallingPreference.getValue()).intValue();
            } else {
                wifiPreference = getDefaultWifiCallingPreference();
            }
            Log.d(TAG, "onPreferenceChange user selected status : wifiStatus " + status +
                    " wifiPreference: " + wifiPreference);
            boolean result = setWifiCallingPreference(status, wifiPreference);
            if (result) {
                loadWifiCallingPreference(status, wifiPreference);
            }
            return result;
        } else if (preference == mWifiCallingPreference) {
            final int wifiPreference = Integer.parseInt(objValue.toString());
            final int status = mWifiCallingSetting.isChecked() ?
                    ImsConfig.WifiCallingValueConstants.ON :
                    ImsConfig.WifiCallingValueConstants.OFF;
            Log.d(TAG, "onPreferenceChange user selected wifiPreference: status " + status +
                    " wifiPreference: " + wifiPreference);
            boolean result = setWifiCallingPreference(status, wifiPreference);
            if (result) {
                loadWifiCallingPreference(status, wifiPreference);
            }
            return result;
        }
        return true;
    }

    private String getWifiPreferenceString(int wifiPreference) {
        switch (wifiPreference) {
            case WIFI_PREFERRED:
                return (getString(R.string.wifi_preferred));
            case WIFI_ONLY:
                return (getString(R.string.wifi_only));
            case CELLULAR_PREFERRED:
                return (getString(R.string.cellular_preferred));
            case CELLULAR_ONLY:
                return (getString(R.string.cellular_only));
            case WIFI_PREF_NONE:
            default:
                return (getString(R.string.wifi_pref_none));
        }
    }

    private ImsConfigListener imsConfigListener = new ImsConfigListener.Stub() {
        public void onGetVideoQuality(int status, int quality) {
            //TODO not required as of now
        }

        public void onSetVideoQuality(int status) {
            //TODO not required as of now
        }

        public void onGetFeatureResponse(int feature, int network, int value, int status) {
            //TODO not required as of now
        }

        public void onSetFeatureResponse(int feature, int network, int value, int status) {
            //TODO not required as of now
        }

        public void onGetPacketCount(int status, long packetCount) {
            //TODO not required as of now
        }

        public void onGetPacketErrorCount(int status, long packetErrorCount) {
            //TODO not required as of now
        }

        public void onGetWifiCallingPreference(int status, int wifiCallingStatus,
                int wifiCallingPreference) {
            if (hasRequestFailed(status)) {
                wifiCallingStatus = ImsConfig.WifiCallingValueConstants.OFF;
                wifiCallingPreference = ImsConfig.WifiCallingPreference.WIFI_PREF_NONE;
                Log.e(TAG, "onGetWifiCallingPreference: failed. errorCode = " + status);
            }
            Log.d(TAG, "onGetWifiCallingPreference: status = " + wifiCallingStatus +
                    " preference = " + wifiCallingPreference);
            loadWifiCallingPreference(wifiCallingStatus, wifiCallingPreference);
        }

        public void onSetWifiCallingPreference(int status) {
            if (hasRequestFailed(status)) {
                Log.e(TAG, "onSetWifiCallingPreference : set failed. errorCode = " + status);
                Toast.makeText(getApplicationContext(), R.string.
                        ims_set_wifi_calling_preference_failed, Toast.LENGTH_SHORT).show();
                getWifiCallingPreference();
            } else {
                Log.d(TAG, "onSetWifiCallingPreference: set succeeded.");
            }
        }
    };

    private void loadWifiCallingPreference(int status, int preference) {
        Log.d(TAG, "loadWifiCallingPreference status = " + status + " preference = " + preference);
        if (status == ImsConfig.WifiCallingValueConstants.NOT_SUPPORTED) {
            mWifiCallingSetting.setEnabled(false);
            if (preference == ImsConfig.WifiCallingPreference.WIFI_PREF_NONE &&
                    mWifiCallingPreference != null) {
                mWifiCallingPreference.setEnabled(false);
            }
            return;
        }
        mWifiCallingSetting.setChecked(getWifiCallingSettingFromStatus(status));
        if (isCarriers()) {
            boolean isTurnOn = getWifiCallingSettingFromStatus(status);
            mSwitch.setChecked(isTurnOn);
            mPrefCate.setEnabled(isTurnOn);
            Set<String> set = mPrefenceIndex.keySet();
            for(String prefence : set){
                if(mPrefenceIndex.get(prefence).equals(preference)){
                    mSelection = mPrefenceIndex.get(prefence);
                    updateSelection(prefence);
                }
            }
            Intent intent = new Intent(isTurnOn ? "com.android.wificall.TURNON"
                    : "com.android.wificall.TURNOFF");
            intent.putExtra("preference", preference);
            sendBroadcast(intent);
        }
        if (mWifiCallingPreference != null) {
            mWifiCallingPreference.setValue(String.valueOf(preference));
            mWifiCallingPreference.setSummary(getWifiPreferenceString(preference));
        }
    }

    private void getWifiCallingPreference() {
        try {
            if (mImsConfig != null) {
                mImsConfig.getWifiCallingPreference(imsConfigListener);
            } else {
                loadWifiCallingPreference(ImsConfig.WifiCallingValueConstants.OFF,
                        ImsConfig.WifiCallingPreference.WIFI_PREF_NONE);
                Log.e(TAG, "getWifiCallingPreference failed. mImsConfig is null");
            }
        } catch (ImsException e) {
            Log.e(TAG, "getWifiCallingPreference failed. Exception = " + e);
        }
    }

    private boolean setWifiCallingPreference(int wifiCallingStatus, int wifiCallingPreference) {
        try {
            if (mImsConfig != null) {
                mImsConfig.setWifiCallingPreference(wifiCallingStatus,
                        wifiCallingPreference, imsConfigListener);
            } else {
                Log.e(TAG, "setWifiCallingPreference failed. mImsConfig is null");
                return false;
            }
        } catch (ImsException e) {
            Log.e(TAG, "setWifiCallingPreference failed. Exception = " + e);
            return false;
        }
        return true;
    }

    private boolean getWifiCallingSettingFromStatus(int status) {
        switch (status) {
            case ImsConfig.WifiCallingValueConstants.ON:
                return true;
            case ImsConfig.WifiCallingValueConstants.OFF:
            case ImsConfig.WifiCallingValueConstants.NOT_SUPPORTED:
            default:
                return false;
        }
    }

    private boolean hasRequestFailed(int result) {
        return (result != ImsConfig.OperationStatusConstants.SUCCESS);
    }

    private boolean isWifiCallingPreferenceSupported() {
        return getApplicationContext().getResources().getBoolean(
                R.bool.config_wifi_calling_preference_supported);
    }

    private int getDefaultWifiCallingPreference() {
        return getApplicationContext().getResources().getInteger(
                R.integer.config_default_wifi_calling_preference);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference.getKey().equals(KEY_WIFI_CALLING_CONNECTION_PREFERENCE)) {
            changeToPreference();
            return true;
        }
        updateSelection(preference.getKey());
        int state = mSwitch.isChecked() ?
                ImsConfig.WifiCallingValueConstants.ON :
                ImsConfig.WifiCallingValueConstants.OFF;
        boolean result = setWifiCallingPreference(state, mSelection);
        if (result) {
            loadWifiCallingPreference(state, mSelection);
        }
        return true;
    }

    // Control the three checkbox: only one should be selected.
    private void updateSelection(String preferenceKey){
        if(preferenceKey == null){
            Log.i(TAG, "updateSelection is null");
            return;
        }
        for(int index = 0; index < mCheckboxPref.size(); index ++){
            CheckBoxPreference checkbox = mCheckboxPref.get(index);
            if(preferenceKey.equals(checkbox.getKey())){
                checkbox.setChecked(true);
                mSelection = mPrefenceIndex.get(preferenceKey);
            }else{
                checkbox.setChecked(false);
            }
        }
        Log.i(TAG, "updateSelection with mSelect : " + mSelection + " Checkbox : " + preferenceKey);
    }

    @Override
    public void onBackPressed() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen.findPreference(KEY_WIFI_CALLING_PREFERRED_SCREEN) != null) {
            screen.removePreference(mPrefCate);
            screen.addPreference(mWifiCallingConnectPre);
            screen.addPreference(mWifiCallingHelp);
            mSwitch.setVisibility(View.GONE);
            mStatus = 1;
        } else {
            super.onBackPressed();
        }
    }

    private void changeToPreference(){
        mStatus = 2;
        PreferenceScreen screen = getPreferenceScreen();
        screen.removePreference(mWifiCallingConnectPre);
        screen.removePreference(mWifiCallingHelp);
        screen.addPreference(mPrefCate);
        mSwitch.setVisibility(View.VISIBLE);
    }

}
