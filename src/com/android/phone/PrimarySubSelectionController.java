/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
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
 *
 */

package com.android.phone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;

import com.codeaurora.telephony.msim.CardSubscriptionManager;
import com.codeaurora.telephony.msim.ModemStackController;
import com.codeaurora.telephony.msim.MSimPhoneFactory;
import com.codeaurora.telephony.msim.MSimUiccController;
import com.codeaurora.telephony.msim.Subscription;
import com.codeaurora.telephony.msim.SubscriptionData;
import com.codeaurora.telephony.msim.SubscriptionManager;

public class PrimarySubSelectionController extends Handler {

    private static final String TAG = "PrimarySubSelectionController";
    private static final boolean DEBUG = true;
    static final int PHONE_COUNT = MSimTelephonyManager.getDefault().getPhoneCount();

    private static PrimarySubSelectionController instance;
    SubscriptionManager mSubscriptionManager;
    CardSubscriptionManager mCardSubscriptionManager;
    ModemStackController mModemStackController;
    private boolean mAllCardsAbsent = true;
    private boolean mCardChanged = false;
    private boolean mModemStackReady = false;
    private boolean mRestoreDdsToPrimarySub = false;
    private boolean[] mIccLoaded;

    private static final String PREF_KEY_SUBSCRIPTION = "subscription";
    public static final String CONFIG_LTE_SUB_SELECT_MODE = "config_lte_sub_select_mode";

    private static final int MSG_SET_LTE_DONE = 1;
    private static final int MSG_ALL_CARDS_AVAILABLE = 2;
    private static final int MSG_CONFIG_LTE_DONE = 3;
    private static final int MSG_MODEM_STACK_READY = 4;

    private final Context mContext;

    private PrimarySubSelectionController(Context context) {
        mContext = context;
        mSubscriptionManager = SubscriptionManager.getInstance();
        mCardSubscriptionManager = CardSubscriptionManager.getInstance();
        mModemStackController = ModemStackController.getInstance();
        mCardSubscriptionManager.registerForAllCardsInfoAvailable(this,
                MSG_ALL_CARDS_AVAILABLE, null);

        mIccLoaded = new boolean[PHONE_COUNT];
        for (int i = 0; i < PHONE_COUNT; i++) {
            mIccLoaded[i] = false;
        }

        if (mModemStackController != null) {
            mModemStackController.registerForStackReady(this, MSG_MODEM_STACK_READY, null);
        }

        IntentFilter filter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mContext.registerReceiver(mIccLoadReceiver, filter);
    }

    private void logd(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    private BroadcastReceiver mIccLoadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                final int sub = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY,
                        MSimConstants.DEFAULT_SUBSCRIPTION);
                final String stateExtra = intent
                        .getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                logd("ACTION_SIM_STATE_CHANGED intent received on sub = " + sub
                        + "SIM STATE IS " + stateExtra);
                mIccLoaded[sub] = false;
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra)) {
                    mIccLoaded[sub] = true;
                    int currentSub = getCurrentSub();
                    logd("ACTION_SIM_STATE_CHANGED current defalut dds :"
                            + MSimPhoneFactory.getDataSubscription());
                    logd("ACTION_SIM_STATE_CHANGED  primay sub is " + currentSub
                            + " , icc loaded sub is " + sub);
                    if (mSubscriptionManager == null
                            || MSimPhoneFactory.getDataSubscription() == currentSub) {
                        mRestoreDdsToPrimarySub = false;
                        return;
                    }

                    logd("ACTION_SIM_STATE_CHANGED mRestoreDdsToPrimarySub: "
                            + mRestoreDdsToPrimarySub);
                    if (mRestoreDdsToPrimarySub) {
                        if (sub == currentSub) {
                            logd("restore dds to primary card");
                            mSubscriptionManager.setDataSubscription(sub, null);
                            mRestoreDdsToPrimarySub = false;
                        }
                    }
                }
                logd("ACTION_SIM_STATE_CHANGED intent received on sub = " + sub
                        + "icc is loaded ? " + mIccLoaded[sub]);
            }
        }
    };

    protected boolean isCardsInfoChanged(int sub) {
        String iccId = getIccId(sub);
        String iccIdInSP = PreferenceManager.getDefaultSharedPreferences(mContext).getString(
                PREF_KEY_SUBSCRIPTION + sub, null);
        logd("sub" + sub + " icc id=" + iccId + ", icc id in sp=" + iccIdInSP);
        return !TextUtils.isEmpty(iccId) && !iccId.equals(iccIdInSP);
    }

    private void saveLteSubSelectMode() {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                CONFIG_LTE_SUB_SELECT_MODE, isManualConfigMode() ? 0 : 1);
    }

    private boolean isManualConfigMode() {
        return isPrimaryLteSubEnabled() && isPrimarySetable()
                && getPrimarySub() == -1;
    }

    private SubscriptionData getSubData(int sub){
        UiccCard uiccCard = MSimUiccController.getInstance().getUiccCard(sub);
        if (uiccCard != null && uiccCard.getCardState() == CardState.CARDSTATE_PRESENT) {
            return mCardSubscriptionManager.getCardSubscriptions(sub);
        }
        return null;
    }

    private String getIccId(int sub) {
        SubscriptionData subscriptionData = getSubData(sub);

        if (subscriptionData != null) {
            return subscriptionData.getIccId();
        }
        return null;
    }

    public void setRestoreDdsToPrimarySub(boolean restoreDdsToPrimarySub) {
        mRestoreDdsToPrimarySub = restoreDdsToPrimarySub;
    }

    private void loadStates() {
        mCardChanged = false;
        for (int i = 0; i < PHONE_COUNT; i++) {
            if (isCardsInfoChanged(i)) {
                mCardChanged = true;
            }
        }
        mAllCardsAbsent = isAllCardsAbsent();
    }

    private boolean isAllCardsAbsent() {
        for (int i = 0; i < PHONE_COUNT; i++) {
            UiccCard uiccCard = MSimUiccController.getInstance().getUiccCard(i);
            if (uiccCard == null || uiccCard.getCardState() != CardState.CARDSTATE_ABSENT) {
                logd("card state on sub" + i + " not absent");
                return false;
            }
        }
        logd("all cards absent");
        return true;
    }

    public boolean isSubActivated(int sub) {
        SubscriptionData subscriptionData = getSubData(sub);
        if (subscriptionData != null) {
            for (int index = 0; index < subscriptionData.getLength(); index++) {
                if (Subscription.SubscriptionStatus.SUB_ACTIVATED
                        == subscriptionData.subscription[index].subStatus) {
                    return true;
                }
            }
        }
        return false;
    }

    private void saveSubscriptions() {
        for (int i = 0; i < PHONE_COUNT; i++) {
            String iccId = getIccId(i);
            if (iccId != null) {
                logd("save subscription on sub" + i + ", iccId :" + iccId);
                PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                        .putString(PREF_KEY_SUBSCRIPTION + i, iccId).commit();
            }
        }
    }

    private void configPrimaryLteSub() {
        int sub = -1;
        if (!isPrimarySetable()) {
            logd("primary is not setable in any sub!");
        } else {
            int primarySub = getPrimarySub();
            int currentSub = getCurrentSub();
            logd("primary sub is " + primarySub);
            logd("current sub is " + currentSub);
            logd("is card changed? " + mCardChanged);
            if (primarySub == -1 && (mCardChanged || currentSub == -1)) {
                sub = 0;
            } else if (primarySub != -1 && (mCardChanged || currentSub != primarySub)) {
                sub = primarySub;
            }
            if (sub != -1 && currentSub == sub && !mCardChanged) {
                logd("primary sub and network mode are all correct, just notify");
                obtainMessage(MSG_CONFIG_LTE_DONE).sendToTarget();
                return;
            } else if (sub == -1) {
                logd("card not changed and primary sub is correct, do nothing");
                return;
            }
        }
        setPreferredNetwork(sub, obtainMessage(MSG_CONFIG_LTE_DONE));
    }

    protected void onConfigLteDone(Message msg) {
        int currentSub = getCurrentSub();
        if (currentSub != -1 && mSubscriptionManager != null) {
            logd("onConfigLteDone currentSub " + currentSub + " is icc loaded ? "
                    + mIccLoaded[currentSub]);
            if (mIccLoaded[currentSub] && MSimPhoneFactory.getDataSubscription() != currentSub) {
                mSubscriptionManager.setDataSubscription(currentSub, null);
                mRestoreDdsToPrimarySub = false;
            } else {
                mRestoreDdsToPrimarySub = true;
            }
        }

        boolean isManualConfigMode = isManualConfigMode();
        logd("onConfigLteDone isManualConfigMode " + isManualConfigMode);
        if (isManualConfigMode) {
            Intent intent = new Intent(mContext, PreferredLTESubSelector.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            try {
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                logd("can not start activity " + intent);
            }
        }
    }

    private void setPrimarySub(){
        if (mAllCardsAbsent) {
            logd("all cards are absent, do not set primary sub.");
            return;
        }

        if (!mModemStackReady) {
            logd("modem stack is not ready, do not set primary sub.");
            return;
        }

        if (isPrimaryLteSubEnabled()) {
            logd("primary sub config feature is enabled!");
            configPrimaryLteSub();
        }
        saveSubscriptions();
        saveLteSubSelectMode();

        //just set NW mode once when all cards available and modem stack ready,
        //so once set done, then reset the value to avoid to set NW mode again
        //and again due to receive the modem stack ready events so many times.
        mAllCardsAbsent = true;
        mModemStackReady = false;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SET_LTE_DONE:
                logd("on EVENT MSG_SET_LTE_DONE");
                onSetLteDone(msg);
                break;
            case MSG_MODEM_STACK_READY:
                logd("on EVENT MSG_MODEM_STACK_READY");
                mModemStackReady = true;
                setPrimarySub();
                break;
            case MSG_ALL_CARDS_AVAILABLE:
                logd("on EVENT MSG_ALL_CARDS_AVAILABLE");
                // reset states and load again by new card info
                loadStates();
                setPrimarySub();
                break;
            case MSG_CONFIG_LTE_DONE:
                logd("on EVENT MSG_CONFIG_LTE_DONE");
                onConfigLteDone(msg);
                break;
        }

    }

    public static void init(Context context) {
        synchronized (PrimarySubSelectionController.class) {
            if (instance == null) {
                instance = new PrimarySubSelectionController(context);
            }
        }
    }

    public static PrimarySubSelectionController getInstance() {
        synchronized (PrimarySubSelectionController.class) {
            if (instance == null) {
                throw new RuntimeException("PrimarySubSelectionController was not initialize!");
            }
            return instance;
        }
    }

    private Request pending;

    private class PrefNetworkSetCommand extends Handler {
        private final int mSubscription;
        private final int mPrefNetwork;

        private static final int EVENT_GET_PREFERRED_NETWORK = 0;
        private static final int EVENT_SET_PREFERRED_NETWORK = 1;

        private PrefNetworkSetCommand(int subscription, int prefNetwork) {
            super(Looper.getMainLooper());
            mSubscription = subscription;
            mPrefNetwork = prefNetwork;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SET_PREFERRED_NETWORK:
                    handleSetPreferredNetwork(msg);
                    break;
                case EVENT_GET_PREFERRED_NETWORK:
                    updateNetworkModeToDbFromModem(msg);
                    break;

            }
        }

        private void updateNetworkModeToDbFromModem(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            logd("get modem NW mode done, " + ar.exception);
            if (ar.exception == null) {
                int modemNetworkMode = ((int[]) ar.result)[0];
                logd("restore modemNetworkMode " + modemNetworkMode + " to DB for sub "
                        + mSubscription);

                MSimTelephonyManager.putIntAtIndex(mContext.getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        mSubscription,
                        modemNetworkMode);
            }
        }

        private void handleSetPreferredNetwork(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            logd("set " + mPrefNetwork + " for sub" + mSubscription + " done, " + ar.exception);
            if (ar.exception != null) {
                MSimPhoneFactory.getPhone(mSubscription).getPreferredNetworkType(
                        obtainMessage(EVENT_GET_PREFERRED_NETWORK));
            }

            Message callback = (Message) ar.userObj;
            if (callback != null) {
                callback.sendToTarget();
            }
        }

        private void request(final Message msg) {
            logd("set " + mPrefNetwork + " for sub" + mSubscription);
            post(new Runnable() {
                public void run() {
                    logd("save network mode " + mPrefNetwork + " for sub" + mSubscription
                            + " to DB");
                    MSimTelephonyManager.putIntAtIndex(mContext.getContentResolver(),
                            android.provider.Settings.Global.PREFERRED_NETWORK_MODE, mSubscription,
                            mPrefNetwork);
                    MSimPhoneFactory.getPhone(mSubscription).setPreferredNetworkType(
                            mPrefNetwork, obtainMessage(EVENT_SET_PREFERRED_NETWORK, msg));
                }
            });
        }
    }

    private class Request extends Handler {

        final Message mCallback;
        final List<PrefNetworkSetCommand> commands;

        private static final int EVENT_SWITCH_LTESUB = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SWITCH_LTESUB:
                    handleSwitchLteSubDone(msg);
                    break;
            }
        }

        private void handleSwitchLteSubDone(Message msg) {
            int current = -1;
            int index = (Integer) msg.obj;
            if (++index < commands.size()) {
                commands.get(index).request(obtainMessage(EVENT_SWITCH_LTESUB, index));
            } else {
                response(mCallback);
            }
        }

        private Request(int sub, int networkMode, Message callback) {
            super(Looper.getMainLooper());
            mCallback = callback;
            commands = new ArrayList<PrefNetworkSetCommand>();
            if (networkMode != Phone.NT_MODE_GSM_ONLY) {
                for (int index = 0; index < PHONE_COUNT; index++) {
                    if (index != sub)
                        commands.add(new PrefNetworkSetCommand(index, Phone.NT_MODE_GSM_ONLY));
                }
            }
            if (sub >= 0 && sub < PHONE_COUNT)
                commands.add(new PrefNetworkSetCommand(sub, networkMode));
        }

        private void start() {
            if (commands.isEmpty()) {
                logd("no command sent");
                response(mCallback);
            } else {
                commands.get(0).request(obtainMessage(EVENT_SWITCH_LTESUB, 0));
            }
        }
    }

    public int getPrimarySub() {
        return getPriority(retrievePriorities(), IINList.getDefault(mContext).getHighestPriority());
    }

    public void setPreferredNetwork(int sub, Message callback) {
        int network = IINList.getDefault(mContext).getIINPrefNetwork(getSubData(sub));
        if (sub != -1 && network == -1) {
            logd("network mode is -1 , can not set primary card ");
            return;
        }
        if (pending != null) {
            logd("has pending request, ignore!");
            response(callback);
        } else {
            logd("try to set " + network + " on sub" + sub);
            pending = new Request(sub, network, obtainMessage(MSG_SET_LTE_DONE, callback));
            pending.start();
        }
    }

    public boolean isPrimarySetable() {
        Map<Integer, Integer> priorities = retrievePriorities();
        int unsetableCount = getCount(priorities, -1);
        return unsetableCount < priorities.size();
    }

    public boolean isPrimaryLteSubEnabled() {
        return SystemProperties.getBoolean("persist.radio.primarycard", false)
                && (PHONE_COUNT > 1);
    }

    public int getCurrentSub() {
        for (int index = 0; index < PHONE_COUNT; index++) {
            int current = getPreferredNetworkFromDb(index);
            if (current == Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE
                    || current == Phone.NT_MODE_TD_SCDMA_GSM_WCDMA) {
                return index;
            }
        }
        return -1;
    }

    private Map<Integer, Integer> retrievePriorities() {
        Map<Integer, Integer> priorities = new HashMap<Integer, Integer>();
        if (mCardSubscriptionManager.isValidCards()) {
            for (int index = 0; index < PHONE_COUNT; index++) {
                priorities.put(
                        index,
                        IINList.getDefault(mContext).getIINPriority(
                                mCardSubscriptionManager.getCardSubscriptions(index)));
            }
        }
        return priorities;
    }

    private int getPriority(Map<Integer, Integer> priorities, Integer higherPriority) {
        int count = getCount(priorities, higherPriority);
        if (count == 1) {
            return getKey(priorities, higherPriority);
        } else if (count > 1) {
            return -1;
        } else if (higherPriority > 0) {
            return getPriority(priorities, --higherPriority);
        } else {
            return -1;
        }
    }

    private int getCount(Map<Integer, Integer> priorities, int priority) {
        int count = 0;
        for (Integer key : priorities.keySet()) {
            if (priorities.get(key) == priority) {
                count++;
            }
        }
        return count;
    }

    private Integer getKey(Map<Integer, Integer> map, int priority) {
        for (Integer key : map.keySet()) {
            if (map.get(key) == priority) {
                return key;
            }
        }
        return null;
    }

    private int getPreferredNetworkFromDb(int sub) {
        int nwMode = -1;
        try {
            nwMode = MSimTelephonyManager.getIntAtIndex(mContext.getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, sub);
        } catch (SettingNotFoundException snfe) {
        }
        return nwMode;
    }

    private void onSetLteDone(Message msg) {
        pending = null;
        response((Message) msg.obj);
    }

    private void response(Message callback) {
        if (callback == null) {
            return;
        }
        if (callback.getTarget() != null) {
            callback.sendToTarget();
        } else {
            Log.w(TAG, "can't response the result, replyTo and target are all null!");
        }
    }
}
