/*
 * Copyright (c) 2012 The Linux Foundation. All rights reserved.
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
 *     * Neither the name of The Linux Foundation, Inc. nor the names of its
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.codeaurora.telephony.msim.MSimPhoneFactory;
import com.codeaurora.telephony.msim.MSimUiccController;
import com.codeaurora.telephony.msim.SubscriptionManager;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

public class XDivertUtility {
    static final String LOG_TAG = "XDivertUtility";
    private static final int SIM_RECORDS_LOADED = 1;
    private static final int EVENT_SUBSCRIPTION_DEACTIVATED = 2;
    private static final int MESSAGE_SET_CFNR = 3;
    private static final int EVENT_ICC_ABSENT = 4;

    private static final String SIM_IMSI = "sim_imsi_key";
    private static final String SIM_NUMBER = "sim_number_key";

    public static final String LINE1_NUMBERS = "Line1Numbers";

    private static final int SUB1 = 0;
    private static final int SUB2 = 1;

    private MSimCallNotifier mCallNotifier;
    private Context mContext;
    private Phone mPhone;
    private MSimPhoneGlobals mApp;
    private UiccCard mUiccCard;
    protected static XDivertUtility sMe;
    private BroadcastReceiver mReceiver;

    private String[] mImsiFromSim;
    private String[] mStoredImsi;
    private String[] mStoredOldImsi;
    private String[] mLineNumber;
    private String[] mOldLineNumber;

    private int mNumPhones = 0;
    private boolean[] mHasImsiChanged;

    public XDivertUtility() {
        sMe = this;
    }

    static XDivertUtility init(MSimPhoneGlobals app, Phone phone ,
            MSimCallNotifier callNotifier, Context context) {
        synchronized (XDivertUtility.class) {
            if (sMe == null) {
                sMe = new XDivertUtility(app, phone, callNotifier, context);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sMe);
            }
            return sMe;
        }
    }

    private XDivertUtility(MSimPhoneGlobals app, Phone phone, MSimCallNotifier callNotifier,
            Context context) {
        Log.d(LOG_TAG, "onCreate()...");
        SubscriptionManager subMgr = SubscriptionManager.getInstance();

        mApp = app;
        mPhone = phone;
        mCallNotifier = callNotifier;
        mContext = context;

        mReceiver = new XDivertBroadcastReceiver();
        mNumPhones = MSimTelephonyManager.getDefault().getPhoneCount();

        mImsiFromSim = new String[mNumPhones];
        mStoredImsi = new String[mNumPhones];
        mStoredOldImsi = new String[mNumPhones];
        mLineNumber = new String[mNumPhones];
        mOldLineNumber = new String[mNumPhones];
        mHasImsiChanged = new boolean[mNumPhones];

        for (int i = 0; i < mNumPhones; i++) {
            subMgr.registerForSubscriptionDeactivated(i, mHandler,
                    EVENT_SUBSCRIPTION_DEACTIVATED, null);
            // register for SIM_RECORDS_LOADED
            mPhone = app.getPhone(i);
            mPhone.registerForSimRecordsLoaded(mHandler, SIM_RECORDS_LOADED, i);

            // register for EVENT_ICC_ABSENT
            mUiccCard = getUiccCard(i);
            mUiccCard.registerForAbsent(mHandler, EVENT_ICC_ABSENT, i);

            mHasImsiChanged[i] = true;
            mStoredOldImsi[i] = getSimImsi(i);
            mOldLineNumber[i] = getNumber(i);
        }
        // Register for intent broadcasts.
        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    static XDivertUtility getInstance() {
        return sMe;
    }

    /**
     * Receiver for intent broadcasts the XDivertUtility cares about.
     */
    private class XDivertBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(LOG_TAG,"Action intent recieved:"+action);
            //gets the subscription information ( "0" or "1")
            int subscription = intent.getIntExtra(SUBSCRIPTION_KEY, mApp.getDefaultSubscription());
            if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                Phone phone = mApp.getPhone(subscription);
                phone.unregisterForSimRecordsLoaded(mHandler);
                phone.registerForSimRecordsLoaded(mHandler, SIM_RECORDS_LOADED, subscription);
            }
        }
    }

    private boolean isAllSubActive() {
        for (int i = 0; i < mNumPhones; i++) {
            if (!SubscriptionManager.getInstance().isSubActive(i)) return false;
        }
        Log.d(LOG_TAG, " isAllSubActive true");
        return true;
    }

    protected int getNextPhoneId(int phoneId) {
        int nextPhoneId = ++phoneId;
        if (nextPhoneId >= mNumPhones) {
            nextPhoneId = 0;
        }
        return nextPhoneId;
    }

    private boolean isImsiChanged(int phoneId) {
         Log.d(LOG_TAG, "isImsiChanged  phoneId: " + phoneId);
        if ((mStoredOldImsi[phoneId] != null) &&
                (mImsiFromSim[phoneId] != null) &&
                mImsiFromSim[phoneId].equals(mStoredOldImsi[phoneId])) {
            Log.d(LOG_TAG, "isImsiChanged: not changed ");
            return false;
        }
        int nPhoneId = getNextPhoneId(phoneId);
        if ((mStoredOldImsi[nPhoneId] != null) &&
                (mImsiFromSim[phoneId] != null) &&
                mImsiFromSim[phoneId].equals(mStoredOldImsi[nPhoneId])) {
            Log.d(LOG_TAG, "isImsiChanged: not changed Matched with other slot");
            setSimImsi(mStoredOldImsi[nPhoneId], phoneId);
            storeNumber(mOldLineNumber[nPhoneId], phoneId);
            if (mImsiFromSim[nPhoneId] == null) {
                Log.d(LOG_TAG, "isImsiChanged: updatting other SIM values");
                setSimImsi(mStoredOldImsi[phoneId], nPhoneId);
                storeNumber(mOldLineNumber[phoneId], nPhoneId);
            }

            return false;
        }
        Log.d(LOG_TAG, "isImsiChanged: TRUE ");
       return true;
    }

    void updateOldValues() {
        for (int i = 0; i < mNumPhones; i++) {
            mStoredOldImsi[i] = mImsiFromSim[i];
            mOldLineNumber[i] = mLineNumber[i];
        }
        Log.d(LOG_TAG, "updateOldValues: mStoredOldImsi[SUB1] = " +
                mStoredOldImsi[SUB1] + "mStoredOldImsi[SUB2] = " +
                mStoredOldImsi[SUB2] + "mOldLineNumber[SUB1] = " +
                mOldLineNumber[SUB1] + "mOldLineNumber[SUB2] = " +
                mOldLineNumber[SUB2]);

    }

    void handleSimRecordsLoaded(int slot) {
        boolean status = false;
        int phoneId = slot;
        Log.d(LOG_TAG, "phoneId = " + phoneId);
        // Get the Imsi value from the SIM records. Retrieve the stored Imsi
        // value from the shared preference. If both are same, then read the
        // stored phone number from the shared preference, else prompt the
        // user to enter them.
        mImsiFromSim[phoneId] = MSimTelephonyManager.getDefault().getSubscriberId(phoneId);
        mStoredImsi[phoneId] = getSimImsi(phoneId);
        Log.d(LOG_TAG, "SIM_RECORDS_LOADED mImsiFromSim = " +
                mImsiFromSim[phoneId] + "mStoredImsi = " +
                mStoredImsi[phoneId]);
        if (!isImsiChanged(phoneId)) {
            // Imsi from SIM matches the stored Imsi so get the stored lineNumbers
            mLineNumber[phoneId] = getNumber(phoneId);
            mHasImsiChanged[phoneId] = false;
            Log.d(LOG_TAG, "Stored Line Number = " + mLineNumber[phoneId]);
        } else {
            mHasImsiChanged[phoneId] = true;
        }
        Log.v(LOG_TAG,"mHasImsiChanged[SUB1]: " + mHasImsiChanged[SUB1]
                + " mHasImsiChanged[SUB2]: " + mHasImsiChanged[SUB2]);
        // Only if Imsi has not changed, query for XDivert status from shared pref
        // and update the notification bar.
        if ((!mHasImsiChanged[SUB1]) && (!mHasImsiChanged[SUB2])) {
            updateOldValues();
            status = mCallNotifier.getXDivertStatus();
            mCallNotifier.onXDivertChanged(status);
        } else {
            int nPhoneId = getNextPhoneId(phoneId);
            Log.d(LOG_TAG,"nPhoneId: " + nPhoneId);
            int disablePhoneId = 0;
            boolean disableReq = false;
            boolean updateStatus = false;
            if (isImsiChanged(phoneId)) {
                // Imsi from SIM does not match the stored Imsi.
                // Hence reset the values.
                updateStatus = true;
                setSimImsi(mImsiFromSim[phoneId], phoneId);
                storeNumber(null, phoneId);
            }
            if (!mHasImsiChanged[phoneId] && mImsiFromSim[nPhoneId] != null
                    && mHasImsiChanged[nPhoneId]) {
                disableReq = true;
                disablePhoneId = phoneId;
            }
            if (mHasImsiChanged[phoneId] && mImsiFromSim[nPhoneId] != null
                    && !mHasImsiChanged[nPhoneId]) {
                disableReq = true;
                disablePhoneId = nPhoneId;
            }
            Log.d(LOG_TAG,"Handle next SIM status");
            int state = getSimState(nPhoneId);
                Log.v(LOG_TAG,"Handle next SIM status state: " + state);
            if (state == TelephonyManager.SIM_STATE_ABSENT && !mHasImsiChanged[phoneId]) {
                disableReq = true;
                disablePhoneId = phoneId;
                updateOldValues();
            }
            Log.d(LOG_TAG,"disableReq: " + disableReq + " disablePhoneId: " + disablePhoneId);
            if (disableReq && mCallNotifier.getXDivertStatus()) {
                updateStatus = true;
                Phone cPhone = MSimPhoneFactory.getPhone(disablePhoneId);
                if (cPhone != null) {
                    cPhone.setCallForwardingOption(0, CommandsInterface.CF_REASON_NOT_REACHABLE,
                            mLineNumber[phoneId], 0, mHandler.obtainMessage(MESSAGE_SET_CFNR,
                            disablePhoneId));
                }
            }
            if (updateStatus) {
                mCallNotifier.setXDivertStatus(false);
            }
            if (mImsiFromSim[SUB1] != null && mImsiFromSim[SUB2] != null) {
                updateOldValues();
            }
        }
    }

    Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            Log.d(LOG_TAG,"mHAndler msg: " +msg.what);
            PhoneConstants.State phoneState;
            AsyncResult ar;
            int phoneId;
            switch (msg.what) {
                case SIM_RECORDS_LOADED:
                    Log.d(LOG_TAG, "SIM_RECORDS_LOADED");
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception != null) {
                        break;
                    }
                    phoneId = (Integer)ar.userObj;
                    handleSimRecordsLoaded(phoneId);
                    break;
                case EVENT_ICC_ABSENT:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception != null) {
                        break;
                    }
                    phoneId = (Integer)ar.userObj;
                    Log.d(LOG_TAG, "EVENT_ICC_ABSENT occurred for phoneId " + phoneId);
                case EVENT_SUBSCRIPTION_DEACTIVATED:
                    Log.d(LOG_TAG, "EVENT_SUBSCRIPTION_DEACTIVATED");
                    onSubscriptionDeactivated();
                    break;
                case MESSAGE_SET_CFNR:
                    Log.d(LOG_TAG, "MESSAGE_SET_CFNR");
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        mCallNotifier.setXDivertStatus(false);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    };

    protected boolean checkImsiReady() {
        for (int i = 0; i < mNumPhones; i++) {
            mStoredImsi[i] = getSimImsi(i);
            mImsiFromSim[i] = MSimTelephonyManager.getDefault().getSubscriberId(i);
            // if imsi is not yet read, then above api returns ""
            if ((mImsiFromSim[i] == null)  || (mImsiFromSim[i] == "")) {
                return false;
            } else if ((mStoredImsi[i] == null) || ((mImsiFromSim[i] != null)
                    && (!mImsiFromSim[i].equals(mStoredImsi[i])))) {
                // Imsi from SIM does not match the stored Imsi.
                // Hence reset the values.
                mCallNotifier.setXDivertStatus(false);
                setSimImsi(mImsiFromSim[i], i);
                storeNumber(null, i);
                mHasImsiChanged[i] = true;
            }
        }
        return true;
    }

    // returns the stored Line Numbers
    protected String[] getLineNumbers() {
        return mLineNumber;
    }

    // returns the stored Line Numbers
    public String[] setLineNumbers(String[] lineNumbers) {
        return mLineNumber = lineNumbers;
    }

    // returns the stored Imsi from shared preference
    protected String getSimImsi(int subscription) {
        Log.d(LOG_TAG, "getSimImsi sub = " + subscription);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        return sp.getString(SIM_IMSI + subscription, null);
    }

    // saves the Imsi to shared preference
    protected void setSimImsi(String imsi, int subscription) {
        Log.d(LOG_TAG, "setSimImsi imsi = " + imsi + "sub = " + subscription);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(SIM_IMSI + subscription, imsi);
        editor.apply();
    }

    int getSimState (int phoneId) {
        int state = TelephonyManager.SIM_STATE_UNKNOWN;
        Phone phone = MSimPhoneFactory.getPhone(phoneId);
        IccCard icc = phone.getIccCard();
        if (icc != null) {
            State simState = icc.getState();
            state = simState.ordinal();
        }
        return state;
    }
    // On Subscription deactivation, clear the Xdivert icon from
    // notification bar
    private void onSubscriptionDeactivated() {
        if (mCallNotifier.getXDivertStatus()) {
            int phoneId = 0;
            boolean disableReq = false;
            int disablePhoneId = 0;
            int nPhoneId = getNextPhoneId(phoneId);
            int state = getSimState(phoneId);
            int nState = getSimState(nPhoneId);
            Log.d(LOG_TAG,"onSubscriptionDeactivated SIM state: " + state
                    + " nState: " + nState);
            if (state == TelephonyManager.SIM_STATE_READY
                    && nState == TelephonyManager.SIM_STATE_ABSENT) {
                disablePhoneId =  phoneId;
                disableReq = true;

            } else if (state == TelephonyManager.SIM_STATE_ABSENT
                    && nState == TelephonyManager.SIM_STATE_READY) {
                disablePhoneId =  nPhoneId;
                disableReq = true;
            }
            if (disableReq) {
                Phone phone = MSimPhoneFactory.getPhone(disablePhoneId);
                if (phone != null) {
                    phone.setCallForwardingOption(0, CommandsInterface.CF_REASON_NOT_REACHABLE,
                            mLineNumber[disablePhoneId], 0,
                            mHandler.obtainMessage(MESSAGE_SET_CFNR, disablePhoneId));
                }
                int temp = getNextPhoneId(disablePhoneId);
                mLineNumber[temp] = null;
                mImsiFromSim[temp] = null;
                updateOldValues();
            }
            mCallNotifier.onXDivertChanged(false);
        }
    }

    // returns the stored Line Numbers from shared preference
    protected String getNumber(int subscription) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        return sp.getString(SIM_NUMBER + subscription, null);
    }

    // saves the Line Numbers to shared preference
    protected void storeNumber(String number, int subscription) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(SIM_NUMBER + subscription, number);
        editor.apply();

        // Update the lineNumber which will be passed to XDivertPhoneNumbers
        // to populate the number from next time.
        mLineNumber[subscription] = number;
    }

    // returns the UiccCard associated with the cardIndex
    private UiccCard getUiccCard(int cardIndex) {
        UiccCard uiccCard = null;
        if (!MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            uiccCard = UiccController.getInstance().getUiccCard();
        } else {
            uiccCard = MSimUiccController.getInstance().getUiccCard(cardIndex);
        }
        return uiccCard;
    }
}
