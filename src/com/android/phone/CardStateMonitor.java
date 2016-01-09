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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;

import java.util.List;

public class CardStateMonitor extends Handler {

    private static final String TAG = "CardStateMonitor";
    private final static boolean DEBUG = true;
    static final int PHONE_COUNT = TelephonyManager.getDefault().getPhoneCount();

    private RegistrantList mAllCardsInfoAvailableRegistrants = new RegistrantList();

    private static final int EVENT_ICC_CHANGED = 1;
    private static final int EVENT_ICCID_LOAD_DONE = 2;
    private static final int EVENT_READ_EF_HPLMNWACT_DONE = 3;

    //Indicate Card type if it is 2G, 3G or 4G card.
    private static final int CARD_TYPE_INVALID = 1;
    private static final int CARD_TYPE_2G = 2;
    private static final int CARD_TYPE_3G = 3;
    private static final int CARD_TYPE_4G = 4;

    //Read state for EF_HPLMNwACT to determine 4G cards.
    private static final int EF_READ_NOT_NEEDED = 0;
    private static final int EF_READ_INIT = 1;
    private static final int EF_READ_IN_PROGRESS = 2;
    private static final int EF_READ_COMPLETED = 3;
    private static final int EF_READ_FAILED_OR_NOT_PRESENT = 4;

    public static final int DUMMY_SUB_ID_BASE = SubscriptionManager.MAX_SUBSCRIPTION_ID_VALUE
            - PhoneConstants.MAX_PHONE_COUNT_TRI_SIM;

    /* 5n bytes:
    1 to 3  nth PLMN (highest priority)
    4 to 5  nth PLMN Access Technology Identifier */
    private static final int HPLMN_SEL_DATA_LEN = 5;

    static class CardInfo {
        boolean mLoadingIcc;
        String mIccId;
        String mCardState;
        int mEfReadState = EF_READ_INIT;
        int mCardType = CARD_TYPE_INVALID;

        boolean isEfReadCompleted() {
            return (mEfReadState == EF_READ_NOT_NEEDED || mEfReadState == EF_READ_COMPLETED ||
                    mEfReadState == EF_READ_FAILED_OR_NOT_PRESENT);
        }

        boolean isCardStateEquals(String cardState) {
            return TextUtils.equals(mCardState, cardState);
        }

        boolean isCardAvailable() {
            return !isCardStateEquals(null)
                    && !(isCardStateEquals(CardState.CARDSTATE_PRESENT.toString()) && TextUtils
                            .isEmpty(mIccId));
        }

        private void reset() {
            mLoadingIcc = false;
            mIccId = null;
            mCardState = null;
            mEfReadState = EF_READ_INIT;
            mCardType = CARD_TYPE_INVALID;
        }
    }

    private static boolean mIsShutDownInProgress;
    private CardInfo[] mCards = new CardInfo[PHONE_COUNT];
    private static Context mContext;
    private BroadcastReceiver receiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction()) &&
                    !intent.getBooleanExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, false)) {
                logd("ACTION_SHUTDOWN Received");
                mIsShutDownInProgress = true;
            }
        }
    };

    public CardStateMonitor(Context context) {
        mContext = context;
        for (int index = 0; index < PHONE_COUNT; index++) {
            mCards[index] = new CardInfo();
        }
        UiccController.getInstance().registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(receiver, filter);
    }

    public void dispose() {
        mContext.unregisterReceiver(receiver);
        UiccController.getInstance().unregisterForIccChanged(this);
    }

    public void registerAllCardsInfoAvailable(Handler handler, int what, Object obj) {
        Registrant r = new Registrant(handler, what, obj);
        synchronized (mAllCardsInfoAvailableRegistrants) {
            mAllCardsInfoAvailableRegistrants.add(r);
            for (int index = 0; index < PHONE_COUNT; index++) {
                if (!mCards[index].isCardAvailable()) {
                    return;
                }
            }
            r.notifyRegistrant();
        }
    }

    public void unregisterAllCardsInfoAvailable(Handler handler) {
        synchronized (mAllCardsInfoAvailableRegistrants) {
            mAllCardsInfoAvailableRegistrants.remove(handler);
        }
    }

    public CardInfo getCardInfo(int cardIndex) {
        return mCards[cardIndex];
    }

    public String getIccId(int cardIndex) {
        return mCards[cardIndex].mIccId;
    }

    public static UiccCard getUiccCard(int cardIndex) {
        UiccCard uiccCard = null;
        PhoneBase phone = (PhoneBase) ((PhoneProxy) PhoneFactory.getPhones()[cardIndex])
                .getActivePhone();
        if (mIsShutDownInProgress
                || Settings.Global.getInt(phone.getContext().getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
            return null;
        }
        if (phone.mCi.getRadioState().isOn()) {
            uiccCard = UiccController.getInstance().getUiccCard(cardIndex);
        }
        return uiccCard;
    }

    public int get4gCardSlot() {
        int slotId = -1;
        for (int index = 0; index < PHONE_COUNT; index++) {
            if (mCards[index].mCardType == CARD_TYPE_4G) {
                slotId = index;
                break;
            }
        }
        return slotId;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_ICC_CHANGED:
                logd("on EVENT_ICC_CHANGED");
                onIccChanged((AsyncResult) msg.obj);
                break;
            case EVENT_ICCID_LOAD_DONE:
                logd("on EVENT_ICCID_LOAD_DONE");
                onIccIdLoaded((AsyncResult) msg.obj);
                break;
            case EVENT_READ_EF_HPLMNWACT_DONE:
                logd("on EVENT_READ_EF_HPLMNWACT_DONE");
                onEfLoaded((AsyncResult) msg.obj);
                break;
        }
    }

    private void onEfLoaded(AsyncResult ar) {
        int cardIndex = (Integer) ar.userObj;

        logd("onEfLoaded: Started");

        if (ar.exception != null) {
            logd("EF_HPLMNWACT read with exception = " + ar.exception);
            mCards[cardIndex].mEfReadState = EF_READ_FAILED_OR_NOT_PRESENT;
            notifyAllCardsAvailableIfNeed();
        } else {
            byte[] data = (byte[]) ar.result;
            logd("result=" + IccUtils.bytesToHexString(data));
            int numRec = data.length / HPLMN_SEL_DATA_LEN;
            logd("number of Records=" + numRec);

            for (int i = 0; i < numRec; i++) {
                if ((data[i * HPLMN_SEL_DATA_LEN + 3] & 0x40) != 0) {
                    mCards[cardIndex].mCardType = CARD_TYPE_4G;
                    break;
                }
            }
            mCards[cardIndex].mEfReadState = EF_READ_COMPLETED;
            notifyAllCardsAvailableIfNeed();
        }
        logd("onEfLoaded: Exit. Slot = " + cardIndex + ", mCardType = " +
                mCards[cardIndex].mCardType + ", mEf State = " + mCards[cardIndex].mEfReadState);

    }

    private void onIccIdLoaded(AsyncResult iccIdResult) {
        byte[] data = (byte[]) iccIdResult.result;
        int cardIndex = (Integer) iccIdResult.userObj;
        String iccId = null;
        if (iccIdResult.exception != null) {
            logd("Exception in GET ICCID," + iccIdResult.exception);
        } else {
            iccId = IccUtils.bcdToString(data, 0, data.length);
            logd("get iccid on card" + cardIndex + ", iccId=" + iccId);
        }
        mCards[cardIndex].mLoadingIcc = false;
        if (!TextUtils.isEmpty(iccId)) {
            mCards[cardIndex].mIccId = iccId;
            mCards[cardIndex].mCardState = CardState.CARDSTATE_PRESENT.toString();
            notifyAllCardsAvailableIfNeed();
        }
    }

    private void onIccChanged(AsyncResult iccChangedResult) {
        if (iccChangedResult == null || iccChangedResult.result == null) {
            for (int index = 0; index < PHONE_COUNT; index++) {
                updateCardState(index);
            }
        } else {
            updateCardState((Integer) iccChangedResult.result);
        }
    }

    private void updateCardState(int sub) {
        UiccCard uiccCard = getUiccCard(sub);
        logd("ICC changed on sub" + sub + ", state is "
                + (uiccCard == null ? "NULL" : uiccCard.getCardState()));
        notifyCardAvailableIfNeed(sub, uiccCard);
    }

    private void loadIccId(int sub, UiccCard uiccCard) {
        mCards[sub].mLoadingIcc = true;
        mCards[sub].mEfReadState = EF_READ_INIT;
        mCards[sub].mCardType = CARD_TYPE_INVALID;
        boolean request = false;
        UiccCardApplication validApp = null;
        int numApps = uiccCard.getNumApplications();
        for (int i = 0; i < numApps; i++) {
            UiccCardApplication app = uiccCard.getApplicationIndex(i);
            if (app != null && app.getType() != AppType.APPTYPE_UNKNOWN) {
                validApp = app;
                break;
            }
        }
        if (validApp != null) {
            IccFileHandler fileHandler = validApp.getIccFileHandler();
            if (fileHandler != null) {
                fileHandler.loadEFTransparent(IccConstants.EF_ICCID,
                        obtainMessage(EVENT_ICCID_LOAD_DONE, sub));
                request = true;
            }
        }
        if (!request) {
            mCards[sub].mLoadingIcc = false;
        }
    }

    private void notifyCardAvailableIfNeed(int sub, UiccCard uiccCard) {
        if (uiccCard != null) {
            if(CardState.CARDSTATE_ABSENT == uiccCard.getCardState()){
                logd("notifyCardAvailableIfNeed sim hot swap");
                mCards[sub].mLoadingIcc = false;
                mCards[sub].mIccId = null;
                mCards[sub].mEfReadState = EF_READ_INIT;
                mCards[sub].mCardType = CARD_TYPE_INVALID;
            }

            if (CardState.CARDSTATE_PRESENT == uiccCard.getCardState()
                    && TextUtils.isEmpty(mCards[sub].mIccId)) {
                if (!mCards[sub].mLoadingIcc) {
                    loadIccId(sub, uiccCard);
                }
            } else if (!mCards[sub].isCardStateEquals(uiccCard.getCardState().toString())) {
                mCards[sub].mCardState = uiccCard.getCardState().toString();
                notifyAllCardsAvailableIfNeed();
            }

            readEfHplmnwActIfNeed(sub, uiccCard);
        } else {
            // card is null, means card info is inavailable or the device is in
            // APM, need to reset all card info, otherwise no change will be
            // detected when card info is available again!
            mCards[sub].reset();
        }
    }

    public static boolean isCardDeactivated(int cardIndex) {
        boolean isSubDeactivated = false;
        List<SubscriptionInfo> sirList =
                SubscriptionManager.from(mContext).getActiveSubscriptionInfoList();
        if (sirList != null ) {
            for (SubscriptionInfo sir : sirList) {
                if (sir != null && cardIndex == sir.getSimSlotIndex()) {
                    if (sir.getSubscriptionId() > 0 &&
                            sir.getSubscriptionId() < DUMMY_SUB_ID_BASE &&
                            sir.getSimSlotIndex() >= 0 &&
                            sir.getStatus() == SubscriptionManager.INACTIVE) {
                        isSubDeactivated = true;
                    }
                    break;
                }
            }
        }
        return isSubDeactivated;
    }

    public static boolean isDetect4gCardEnabled() {
        return SystemProperties.getBoolean("persist.radio.primarycard", false) &&
                SystemProperties.getBoolean("persist.radio.detect4gcard", false) &&
                (PHONE_COUNT > 1);
    }

    private void readEfHplmnwActIfNeed(int cardIndex, UiccCard uiccCard) {
        UiccCardApplication app = uiccCard.getApplication(UiccController.APP_FAM_3GPP);

        logd("readEfHplmnwActIfNeed: Started");

        if (!isDetect4gCardEnabled() || CardState.CARDSTATE_PRESENT != uiccCard.getCardState()) {
            //Card is not present or feature not enabled, no need to read the EF.
            logd("readEfHplmnwActIfNeed: Card is Absent or Property Disabled. EXIT!!!");
            mCards[cardIndex].mCardType = CARD_TYPE_INVALID;
            if (mCards[cardIndex].mEfReadState != EF_READ_NOT_NEEDED) {
                mCards[cardIndex].mEfReadState = EF_READ_NOT_NEEDED;
                notifyAllCardsAvailableIfNeed();
            }
        } else if (isCardDeactivated(cardIndex)) {
            //card is deactivated, no need to read the EF
            logd("readEfHplmnwActIfNeed: Card is Deactivated. EXIT!!");
            if (mCards[cardIndex].mEfReadState != EF_READ_NOT_NEEDED) {
                mCards[cardIndex].mEfReadState = EF_READ_NOT_NEEDED;
                notifyAllCardsAvailableIfNeed();
            }
        } else if (app != null && app.getType() != AppType.APPTYPE_USIM &&
                app.getState() == AppState.APPSTATE_READY) {
            //card is not 3G/4G, no need to read the EF
            logd("readEfHplmnwActIfNeed: Not an USIM. EXIT!!!");
            mCards[cardIndex].mCardType = CARD_TYPE_2G;
            if (mCards[cardIndex].mEfReadState != EF_READ_FAILED_OR_NOT_PRESENT) {
                mCards[cardIndex].mEfReadState = EF_READ_FAILED_OR_NOT_PRESENT;
                notifyAllCardsAvailableIfNeed();
            }
        } else if (app != null && app.getState() == AppState.APPSTATE_READY &&
                mCards[cardIndex].mEfReadState != EF_READ_IN_PROGRESS &&
                mCards[cardIndex].mEfReadState != EF_READ_COMPLETED) {
            logd("readEfHplmnwActIfNeed: EF_HPLMNwACT Start read...");
            IccFileHandler iccFh = app.getIccFileHandler();

            if (iccFh != null) {
                //card is atleast 3G card, Read the EF to check if it supports 4G or not.
                mCards[cardIndex].mCardType = CARD_TYPE_3G;
                mCards[cardIndex].mEfReadState = EF_READ_IN_PROGRESS;
                iccFh.loadEFTransparent(IccConstants.EF_HPLMNWACT,
                    obtainMessage(EVENT_READ_EF_HPLMNWACT_DONE, cardIndex));
            } else {
                Log.w(TAG, "IccFileHandler is null");
            }
        }
        logd("readEfHplmnwActIfNeed: Exit. Slot = " + cardIndex + ", mCardType = " +
                mCards[cardIndex].mCardType + ", mEf State = " + mCards[cardIndex].mEfReadState);
    }

    private void notifyAllCardsAvailableIfNeed() {
        for (int index = 0; index < PHONE_COUNT; index++) {
            if (!mCards[index].isCardAvailable() || !mCards[index].isEfReadCompleted()) {
                return;
            }
        }
        mAllCardsInfoAvailableRegistrants.notifyRegistrants();
    }

    static void logd(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
