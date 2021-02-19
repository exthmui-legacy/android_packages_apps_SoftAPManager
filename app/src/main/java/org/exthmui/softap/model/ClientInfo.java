package org.exthmui.softap.model;

import java.util.ArrayList;
import java.util.Arrays;

public class ClientInfo implements Cloneable {
    private String mName;
    private final String mMACAddress;
    private boolean mBlocked;
    private boolean mConnected;
    private String mManufacturer;
    private final ArrayList<String> mIPAddressList = new ArrayList<>();

    public ClientInfo(String mac) {
        mMACAddress = mac;
    }

    public String getMACAddress() {
        return mMACAddress;
    }

    public void setName(String name) {
        mName = name;
    }

    public String getName() {
        return mName;
    }

    public void addIPAddress(String ip) {
        mIPAddressList.add(ip);
    }

    public void setConnected(boolean connected) {
        mConnected = connected;
        if (!connected) mIPAddressList.clear();
    }

    public boolean isConnected() {
        return mConnected;
    }

    public void setBlocked(boolean blocked) {
        mBlocked = blocked;
    }

    public boolean isBlocked() {
        return mBlocked;
    }

    public String[] getIPAddressArray() {
        Object[] objects = mIPAddressList.toArray();
        return Arrays.copyOf(objects, objects.length, String[].class);
    }

    public void setManufacturer(String manufacturer) {
        mManufacturer = manufacturer;
    }

    public String getManufacturer() {
        return mManufacturer;
    }

    @Override
    public ClientInfo clone() {
        try {
            return (ClientInfo) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}
