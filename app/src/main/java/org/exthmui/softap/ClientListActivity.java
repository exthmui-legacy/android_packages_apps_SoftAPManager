package org.exthmui.softap;

import android.app.ActionBar;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.view.MenuItem;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import org.exthmui.softap.model.ClientInfo;

import java.util.ArrayList;

public class ClientListActivity extends FragmentActivity implements SoftApManageService.StatusListener {

    private SoftApManageService.SoftApManageBinder mSoftApManageBinder;
    private SoftApManageConn mSoftApManageConn;
    private ClientListFragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.client_list_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.content, new ClientListFragment())
                    .commit();
        }
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        Intent mSoftApManageService = new Intent(this, SoftApManageService.class);
        startServiceAsUser(mSoftApManageService, UserHandle.CURRENT_OR_SELF);
        mSoftApManageConn = new SoftApManageConn();
        bindServiceAsUser(mSoftApManageService, mSoftApManageConn, Context.BIND_AUTO_CREATE, UserHandle.CURRENT_OR_SELF);
    }

    private void updateClientList() {
        if (mFragment != null && mSoftApManageBinder != null) {
            mFragment.updateClientList(mSoftApManageBinder.getClients());
        }
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        super.onAttachFragment(fragment);
        if (mFragment == null && fragment instanceof ClientListFragment) {
            mFragment = (ClientListFragment) fragment;
            updateClientList();
        }
    }

    @Override
    protected void onDestroy() {
        if (mSoftApManageConn != null) {
            mSoftApManageBinder.removeStatusListener(this);
            unbindService(mSoftApManageConn);
        }
        super.onDestroy();
    }

    @Override
    public void onStatusChanged(int what) {
        if (what == SoftApManageService.STATUS_CLIENTS_REFRESH_DONE ||
            what == SoftApManageService.STATUS_BLOCK_LIST_UPDATED) {
            updateClientList();
        }
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

    public static class ClientListFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceClickListener {

        private PreferenceCategory mConnectedClients;
        private PreferenceCategory mBlockedClients;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.client_list_prefs, rootKey);
            mConnectedClients = findPreference("connected_clients");
            mBlockedClients = findPreference("blocked_clients");
            updateCategory();
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            Intent intent = new Intent(getActivity(), ClientInfoActivity.class);
            intent.putExtra("mac", preference.getKey());
            startActivity(intent);
            return true;
        }

        public void updateClientList(ClientInfo[] arr) {
            ArrayList<String> removeConnectedPrefList = new ArrayList<>();
            ArrayList<String> removeBlockedPrefList = new ArrayList<>();

            addPrefToList(removeConnectedPrefList, mConnectedClients);
            addPrefToList(removeBlockedPrefList, mBlockedClients);

            for (ClientInfo info : arr) {
                if (info.isBlocked()) {
                    removeBlockedPrefList.remove(info.getMACAddress());
                    if (mBlockedClients.findPreference(info.getMACAddress()) != null) {
                        continue;
                    }
                    mBlockedClients.addPreference(makeClientPreference(info));
                } else {
                    removeConnectedPrefList.remove(info.getMACAddress());
                    if (mConnectedClients.findPreference(info.getMACAddress()) != null) {
                        continue;
                    }
                    mConnectedClients.addPreference(makeClientPreference(info));
                }
            }

            for (String key : removeBlockedPrefList) {
                mBlockedClients.removePreferenceRecursively(key);
            }
            for (String key : removeConnectedPrefList) {
                mConnectedClients.removePreferenceRecursively(key);
            }
            updateCategory();
        }

        private void addPrefToList(ArrayList<String> list, PreferenceCategory preferenceCategory) {
            for (int i = 0; i < preferenceCategory.getPreferenceCount(); i++) {
                Preference pref = preferenceCategory.getPreference(i);
                list.add(pref.getKey());
            }
        }

        private Preference makeClientPreference(ClientInfo clientInfo) {
            Preference preference = new Preference(getContext());
            preference.setKey(clientInfo.getMACAddress());
            preference.setTitle(clientInfo.getName());
            preference.setSummary(clientInfo.getMACAddress());
            preference.setOnPreferenceClickListener(this);
            return preference;
        }

        private void updateCategory() {
            if (mConnectedClients != null) {
                if (mConnectedClients.getPreferenceCount() == 0) {
                    mConnectedClients.setVisible(false);
                } else {
                    mConnectedClients.setVisible(true);
                }
            }

            if (mBlockedClients != null) {
                if (mBlockedClients.getPreferenceCount() == 0) {
                    mBlockedClients.setVisible(false);
                } else {
                    mBlockedClients.setVisible(true);
                }
            }
        }
    }

    private class SoftApManageConn implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mSoftApManageBinder = (SoftApManageService.SoftApManageBinder) iBinder;
            mSoftApManageBinder.addStatusListener(ClientListActivity.this);
            updateClientList();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSoftApManageBinder = null;
            finish();
        }
    }
}