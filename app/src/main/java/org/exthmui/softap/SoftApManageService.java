package org.exthmui.softap;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiClient;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.exthmui.softap.model.ClientInfo;
import org.exthmui.softap.oui.MACData;
import org.exthmui.softap.oui.MACDataHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;

public class SoftApManageService extends Service implements WifiManager.SoftApCallback {

    private static final String IP_NEIGHBOR_COMMAND_FORMAT = "ip neigh show dev %s";
    private static final int COLUMN_IP_ADDRESS = 0;
    private static final int COLUMN_MAC_ADDRESS = 2;

    private static final int MSG_STATUS_CHANGED = 1;
    private static final int MSG_UPDATE_CLIENT_LIST = 2;

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_CLIENTS_REFRESHING = 1;
    public static final int STATUS_CLIENTS_REFRESH_DONE = 2;
    public static final int STATUS_ERROR = 3;
    public static final int STATUS_BLOCK_LIST_UPDATED = 4;

    private static File BLOCKED_MAC_ADDRESS_FILE;

    private INetworkManagementService mNetworkManagementService;
    private WifiManager mWifiManager;

    private String mInterfaceName;
    private final ArrayList<String> mBlockedMACList = new ArrayList<>();
    private final ArrayList<StatusListener> mListeners = new ArrayList<>();
    private final HashMap<String, ClientInfo> mClients = new HashMap<>();

    private Thread mClientUpdateThread;
    private Handler mHandler;

    /* Use this receiver to get interface name and register callback */
    private final BroadcastReceiver mApStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                mInterfaceName = intent.getStringExtra(EXTRA_WIFI_AP_INTERFACE_NAME);
                unregisterReceiver(this);
                mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler),SoftApManageService.this);
            }
        }
    };

    public SoftApManageService() {
    }

    private Thread createClientUpdateThread(final List<WifiClient> clients) {
        return new Thread() {
            @Override
            public void run() {
                try {
                    IUpdateClientList(clients);
                    sendStatusChangedMessage(STATUS_NORMAL);
                } catch (Exception e) {
                    e.printStackTrace();
                    sendStatusChangedMessage(STATUS_ERROR);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int ret = super.onStartCommand(intent, flags, startId);
        if (BLOCKED_MAC_ADDRESS_FILE == null) {
            BLOCKED_MAC_ADDRESS_FILE = new File(getDataDir(), "blocked.txt");
        }
        if (mHandler == null) {
            mHandler = new Handler(getMainLooper()) {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    super.handleMessage(msg);
                    if (msg.what == MSG_STATUS_CHANGED) {
                        for (StatusListener listener : mListeners) {
                            listener.onStatusChanged(msg.arg1);
                        }
                    } else if (msg.what == MSG_UPDATE_CLIENT_LIST) {
                        if (mClientUpdateThread != null &&
                                mClientUpdateThread.getState() != Thread.State.NEW &&
                                mClientUpdateThread.getState() != Thread.State.TERMINATED) {
                            mHandler.removeMessages(MSG_UPDATE_CLIENT_LIST);
                            mHandler.sendMessageDelayed(msg, 1000);
                            return;
                        }
                        mClientUpdateThread = createClientUpdateThread((List<WifiClient>) msg.obj);
                        mClientUpdateThread.start();
                    }
                }
            };
        }
        if (mNetworkManagementService == null) {
            mNetworkManagementService = INetworkManagementService.Stub.asInterface(ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        }
        if (mWifiManager == null) {
            mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        }
        if (mBlockedMACList.isEmpty()) {
            updateBlockedMACList();
        }

        if (!MACDataHelper.isInitialized()) {
            MACDataHelper.init(this);
        }

        registerReceiver(mApStatusReceiver, new IntentFilter(WifiManager.WIFI_AP_STATE_CHANGED_ACTION));

        return ret;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mApStatusReceiver);
        mWifiManager.unregisterSoftApCallback(this);
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

    private void IUpdateClientList(List<WifiClient> clients) {

        HashMap<String, ClientInfo> clientInfoHashMap = new HashMap<>();

        sendStatusChangedMessage(STATUS_CLIENTS_REFRESHING);
        // 取得当前连接的设备
        for (WifiClient client : clients) {
            String macAddress = client.getMacAddress().toString();
            ClientInfo info = new ClientInfo(macAddress);
            MACData macData = MACDataHelper.findMACData(macAddress);
            if (macData != null) {
                info.setManufacturer(macData.toString());
            }
            clientInfoHashMap.put(macAddress, info);
        }
        // MAC-IP 匹配
        String res = execCommand(String.format(IP_NEIGHBOR_COMMAND_FORMAT, mInterfaceName));
        if (!TextUtils.isEmpty(res)) {
            String[] lines = res.split("\n");
            for (String line : lines) {
                String[] values = line.split(" ");
                String ipAddress = values[COLUMN_IP_ADDRESS];
                String macAddress = values[COLUMN_MAC_ADDRESS];
                if (!isValidMACAddress(macAddress)) continue;
                if (clientInfoHashMap.containsKey(macAddress)) {
                    ClientInfo info = clientInfoHashMap.get(macAddress);
                    info.addIPAddress(ipAddress);
                }
            }
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

        for (ClientInfo clientInfo : clientInfoHashMap.values()) {
            getClientName(clientInfo);
        }

        mClients.clear();
        mClients.putAll(clientInfoHashMap);

        sendStatusChangedMessage(STATUS_CLIENTS_REFRESH_DONE);
    }

    private boolean isValidMACAddress(String mac) {
        return mac != null && mac.matches("([a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}");
    }

    private boolean isIPV4Address(String ip) {
        return ip != null && ip.matches("((25[0-5]|2[0-4]\\d|((1\\d{2})|([1-9]?\\d)))\\.){3}(25[0-5]|2[0-4]\\d|((1\\d{2})|([1-9]?\\d)))");
    }

    private void sendStatusChangedMessage(int what) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_STATUS_CHANGED;
        msg.arg1 = what;
        mHandler.sendMessage(msg);
    }

    private void getClientName(ClientInfo client) {
        String[] ips = client.getIPAddressArray();
        if (ips == null || ips.length == 0) return;
        try {
            boolean isIPV4 = isIPV4Address(ips[0]);
            String[] ipStr;
            if (isIPV4) {
                ipStr = ips[0].split("\\.");
            } else {
                ipStr = ips[0].split(":");
            }
            byte[] ipBuffer = new byte[ipStr.length];
            for(int i = 0; i < ipStr.length; i++){
                ipBuffer[i] = (byte)(Integer.parseInt(ipStr[i], isIPV4 ? 10 : 16) & 0xff);
            }
            InetAddress inetAddress = InetAddress.getByAddress(ipBuffer);
            client.setName(inetAddress.getHostName());
        } catch (UnknownHostException e) {
            client.setName(ips[0]);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new SoftApManageBinder();
    }

    @Override
    public void onConnectedClientsChanged(List<WifiClient> clients) {
        Message msg = mHandler.obtainMessage();
        msg.what = MSG_UPDATE_CLIENT_LIST;
        msg.obj = clients;
        mHandler.sendMessage(msg);
    }

    private static String execCommand(String cmd) {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuffer stringBuffer = new StringBuffer();
            char[] buff = new char[1024];
            int ch = 0;
            while ((ch = reader.read(buff)) != -1) {
                stringBuffer.append(buff, 0, ch);
            }
            reader.close();
            process.waitFor();
            if (process.exitValue() == 0) {
                return stringBuffer.toString();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return null;
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
