package org.exthmui.softap;

import android.app.ActionBar;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import org.exthmui.softap.model.ClientInfo;

public class ClientInfoActivity extends FragmentActivity implements SoftApManageService.StatusListener {

    private SoftApManageService.SoftApManageBinder mSoftApManageBinder;
    private SoftApManageConn mSoftApManageConn;

    private ClientInfoFragment mFragment;

    private String mMACAddress;
    private ClientInfo mClientInfo;
    private IClientManager mClientManager = new IClientManager() {
        @Override
        public boolean block(boolean val) {
            if (mSoftApManageBinder != null) {
                if (val) {
                    return mSoftApManageBinder.blockClient(mMACAddress);
                } else {
                    return mSoftApManageBinder.unblockClient(mMACAddress);
                }
            }
            return false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mMACAddress = intent.getStringExtra("mac");
        if (TextUtils.isEmpty(mMACAddress)) {
            finish();
            return;
        }

        setContentView(R.layout.client_info_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content, new ClientInfoFragment())
                    .commit();
        }
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        Intent mSoftApManageService = new Intent(this, SoftApManageService.class);
        mSoftApManageConn = new SoftApManageConn();
        bindServiceAsUser(mSoftApManageService, mSoftApManageConn, Context.BIND_AUTO_CREATE, UserHandle.CURRENT_OR_SELF);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case android.R.id.home:
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (mSoftApManageConn != null) {
            unbindService(mSoftApManageConn);
        }
        if (mFragment != null) {
            mFragment.setClientManager(null);
        }
        super.onDestroy();
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        super.onAttachFragment(fragment);
        if (mFragment == null && fragment instanceof ClientInfoFragment) {
            mFragment = (ClientInfoFragment) fragment;
            mFragment.setClientManager(mClientManager);
        }
    }

    private void updateClientInfo() {
        if (mFragment == null || mSoftApManageBinder == null) {
            return;
        }
        mClientInfo = mSoftApManageBinder.getClientByMAC(mMACAddress);
        mFragment.updateClientInfo(mClientInfo);
    }

    @Override
    public void onStatusChanged(int what) {

    }

    private class SoftApManageConn implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mSoftApManageBinder = (SoftApManageService.SoftApManageBinder) iBinder;
            updateClientInfo();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSoftApManageBinder = null;
            finish();
        }
    }


    public static class ClientInfoFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

        private IClientManager mClientManager;
        private Preference prefName;
        private Preference prefMAC;
        private Preference prefIP;
        private Preference prefManufacturer;
        private SwitchPreference prefBlocked;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.client_info_prefs, rootKey);
            prefName = findPreference("name");
            prefIP = findPreference("ip_address");
            prefMAC = findPreference("mac_address");
            prefManufacturer = findPreference("manufacturer");
            prefBlocked = findPreference("blocked");
            prefBlocked.setOnPreferenceChangeListener(this);
        }

        public void setClientManager(IClientManager manager) {
            mClientManager = manager;
        }

        public void updateClientInfo(ClientInfo info) {
            if (info == null) return;
            prefName.setSummary(info.getName());
            prefMAC.setSummary(info.getMACAddress());
            prefIP.setSummary(String.join("\n", info.getIPAddressArray()));
            prefManufacturer.setSummary(info.getManufacturer());
            prefBlocked.setChecked(info.isBlocked());
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (preference == prefBlocked) {
                Boolean val = (Boolean) newValue;
                if (mClientManager != null) {
                    return mClientManager.block(val);
                }
            }
            return false;
        }
    }

    protected interface IClientManager {
        boolean block(boolean val);
    }
}