package org.exthmui.softap;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TetheringManager;
import android.net.TetheredClient;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import org.exthmui.softap.model.ClientInfo;
import org.exthmui.softap.oui.MACData;
import org.exthmui.softap.oui.MACDataHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

public class SoftApManageService extends Service implements TetheringManager.TetheringEventCallback {
    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_CLIENTS_REFRESHING = 1;
    public static final int STATUS_CLIENTS_REFRESH_DONE = 2;
    public static final int STATUS_ERROR = 3;
    public static final int STATUS_BLOCK_LIST_UPDATED = 4;

    private static File BLOCKED_MAC_ADDRESS_FILE;

    private INetworkManagementService mNetworkManagementService;
    private TetheringManager mTetheringManager;

    private final ArrayList<String> mBlockedMACList = new ArrayList<>();
    private final ArrayList<StatusListener> mListeners = new ArrayList<>();
    private final HashMap<String, ClientInfo> mClients = new HashMap<>();

    private Handler mHandler;

    public SoftApManageService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int ret = super.onStartCommand(intent, flags, startId);
        if (BLOCKED_MAC_ADDRESS_FILE == null) {
            BLOCKED_MAC_ADDRESS_FILE = new File(getDataDir(), "blocked.txt");
        }
        if (mHandler == null) {
            mHandler = new Handler(Looper.myLooper());
        }
        if (mNetworkManagementService == null) {
            mNetworkManagementService = INetworkManagementService.Stub.asInterface(ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        }
        if (mTetheringManager == null) {
            mTetheringManager = (TetheringManager) getSystemService(TetheringManager.class);
        }
        if (mBlockedMACList.isEmpty()) {
            updateBlockedMACList();
        }

        if (!MACDataHelper.isInitialized()) {
            MACDataHelper.init(this);
        }

        try {
            mTetheringManager.registerTetheringEventCallback(new HandlerExecutor(mHandler), this);
        } catch (Exception e) {
            // do nothing
        }

        return ret;
    }

    @Override
    public void onDestroy() {
        mTetheringManager.unregisterTetheringEventCallback(this);
        super.onDestroy();
    }

    private void updateBlockedMACList() {
        try {
            mBlockedMACList.clear();
            FileInputStream fis = new FileInputStream(BLOCKED_MAC_ADDRESS_FILE);
            InputStreamReader inputStreamReader = new InputStreamReader(fis);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            while (bufferedReader.ready()) {
                String mac = bufferedReader.readLine();
                blockMACAddress(mac);
            }
            bufferedReader.close();
            inputStreamReader.close();
        } catch (IOException e) {
            // do nothing
        }
    }

    private void saveBlockedMACList() {
        try {
            BLOCKED_MAC_ADDRESS_FILE.createNewFile();
            FileWriter fileWriter = new FileWriter(BLOCKED_MAC_ADDRESS_FILE);
            for (String mac : mBlockedMACList) {
                fileWriter.write(mac);
                fileWriter.write('\n');
            }
            fileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean blockMACAddress(String mac) {
        if (!mBlockedMACList.contains(mac)) {
            try {
                mNetworkManagementService.setFirewallMACAddressRule(mac, false);
                mBlockedMACList.add(mac);
                if (mClients.containsKey(mac)) {
                    mClients.get(mac).setBlocked(true);
                }
                sendStatusChangedMessage(STATUS_BLOCK_LIST_UPDATED);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private boolean unblockMACAddress(String mac) {
        if (mBlockedMACList.contains(mac)) {
            try {
                mNetworkManagementService.setFirewallMACAddressRule(mac, true);
                mBlockedMACList.remove(mac);
                if (mClients.containsKey(mac)) {
                    mClients.get(mac).setBlocked(false);
                }
                sendStatusChangedMessage(STATUS_BLOCK_LIST_UPDATED);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public void onClientsChanged(Collection<TetheredClient> clients) {
        HashMap<String, ClientInfo> clientInfoHashMap = new HashMap<>();
        sendStatusChangedMessage(STATUS_CLIENTS_REFRESHING);
        // 取得当前连接的设备
        for (TetheredClient client : clients) {
            if (client.getTetheringType() != TetheringManager.TETHERING_WIFI) {
                continue;
            }
            String macAddress = client.getMacAddress().toString();
            ClientInfo info = new ClientInfo(macAddress);
            MACData macData = MACDataHelper.findMACData(macAddress);
            if (macData != null) {
                info.setManufacturer(macData.toString());
            }
            for (TetheredClient.AddressInfo addressInfo : client.getAddresses()) {
                info.addIPAddress(addressInfo.getAddress().getAddress().getHostAddress());
                if (!TextUtils.isEmpty(addressInfo.getHostname())) {
                    info.setName(addressInfo.getHostname());
                }
            }
            if (info.getIPAddressArray().length == 0) {
                continue;
            }
            if (TextUtils.isEmpty(info.getName())) {
                info.setName(info.getIPAddressArray()[0]);
            }
            clientInfoHashMap.put(macAddress, info);
        }

        // 取得被屏蔽的设备列表
        for (String blockedMAC : mBlockedMACList) {
            if (clientInfoHashMap.containsKey(blockedMAC)) {
                clientInfoHashMap.get(blockedMAC).setBlocked(true);
            } else {
                ClientInfo info = new ClientInfo(blockedMAC);
                MACData macData = MACDataHelper.findMACData(blockedMAC);
                if (macData != null) {
                    info.setManufacturer(macData.toString());
                }
                info.setBlocked(true);
                clientInfoHashMap.put(blockedMAC, info);
            }
        }

        mClients.clear();
        mClients.putAll(clientInfoHashMap);

        sendStatusChangedMessage(STATUS_CLIENTS_REFRESH_DONE);
    }

    private void sendStatusChangedMessage(int what) {
        for (StatusListener listener : mListeners) {
            listener.onStatusChanged(what);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new SoftApManageBinder();
    }

    public class SoftApManageBinder extends Binder {
        public ClientInfo[] getClients() {
            ClientInfo[] clientInfoArray = new ClientInfo[mClients.size()];
            int i = 0;
            for (ClientInfo clientInfo : mClients.values()) {
                clientInfoArray[i++] = clientInfo.clone();
            }
            return clientInfoArray;
        }

        public ClientInfo getClientByMAC(String macAddress) {
            ClientInfo client = mClients.get(macAddress);
            if (client == null) {
                return null;
            } else {
                return client.clone();
            }
        }

        public boolean blockClient(String macAddress) {
            if (blockMACAddress(macAddress)) {
                saveBlockedMACList();
                return true;
            }
            return false;
        }

        public boolean unblockClient(String macAddress) {
            if (unblockMACAddress(macAddress)) {
                saveBlockedMACList();
                return true;
            }
            return false;
        }

        public boolean addStatusListener(StatusListener listener) {
            if (mListeners.contains(listener)) return false;
            return mListeners.add(listener);
        }

        public boolean removeStatusListener(StatusListener listener) {
            return mListeners.remove(listener);
        }
    }

    public interface StatusListener {
        void onStatusChanged(int what);
    }
}
