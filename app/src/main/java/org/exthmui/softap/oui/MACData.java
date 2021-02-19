package org.exthmui.softap.oui;

import androidx.annotation.NonNull;

public class MACData {
    public String registry;
    public String assignment;
    public String organization;
    public String address;

    @NonNull
    @Override
    public String toString() {
        return String.join("\n", organization, address, registry);
    }
}
