package org.exthmui.softap.oui;

import android.content.Context;
import android.content.res.AssetManager;

import com.csvreader.CsvReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

public class MACDataHelper {

    private static final String MAC_ADDRESS_BLOCK_LARGE_FILE = "oui.csv";
    private static final int MAC_ADDRESS_BLOCK_LARGE_LENGTH = 6;

    private static final String MAC_ADDRESS_BLOCK_MEDIUM_FILE = "mam.csv";
    private static final int MAC_ADDRESS_BLOCK_MEDIUM_LENGTH = 7;

    private static final String MAC_ADDRESS_BLOCK_SMALL_FILE = "oui36.csv";
    private static final int MAC_ADDRESS_BLOCK_SMALL_LENGTH = 9;

    private static final String IAB_FILE = "iab.csv";
    private static final int IAB_LENGTH = 9;

    private static class CsvColumn {
        private static final int REGISTRY = 0;
        private static final int ASSIGNMENT = 1;
        private static final int ORGANIZATION = 2;
        private static final int ADDRESS = 3;
    }

    private static boolean sInitialized = false;

    private static final HashMap<String, MACData> sMACDataMap = new HashMap<>();

    public static boolean isInitialized() {
        return sInitialized;
    }

    public static void init(Context context) {
        AssetManager assetManager = context.getResources().getAssets();

        readList(assetManager, MAC_ADDRESS_BLOCK_LARGE_FILE);
        readList(assetManager, MAC_ADDRESS_BLOCK_MEDIUM_FILE);
        readList(assetManager, MAC_ADDRESS_BLOCK_SMALL_FILE);
        readList(assetManager, IAB_FILE);

        sInitialized = true;
    }

    public static MACData findMACData(String originMAC) {
        String mac = originMAC.replaceAll("[:\\-]", "").toUpperCase();
        String subStringLarge = mac.substring(0, MAC_ADDRESS_BLOCK_LARGE_LENGTH);
        if (sMACDataMap.containsKey(subStringLarge)) {
            return sMACDataMap.get(subStringLarge);
        }

        String subStringMedium = mac.substring(0, MAC_ADDRESS_BLOCK_MEDIUM_LENGTH);
        if (sMACDataMap.containsKey(subStringMedium)) {
            return sMACDataMap.get(subStringMedium);
        }

        String subStringSmall = mac.substring(0, MAC_ADDRESS_BLOCK_SMALL_LENGTH);
        if (sMACDataMap.containsKey(subStringSmall)) {
            return sMACDataMap.get(subStringSmall);
        }

        return null;
    }

    private static void readList(AssetManager manager, String fileName) {
        try {
            InputStream inputStream = manager.open(fileName);
            CsvReader reader = new CsvReader(inputStream, StandardCharsets.UTF_8);
            reader.readHeaders();
            while (reader.readRecord()){
                MACData macData = new MACData();
                macData.registry = reader.get(CsvColumn.REGISTRY);
                macData.assignment = reader.get(CsvColumn.ASSIGNMENT);
                macData.organization = reader.get(CsvColumn.ORGANIZATION);
                macData.address = reader.get(CsvColumn.ADDRESS);
                sMACDataMap.put(macData.assignment, macData);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
