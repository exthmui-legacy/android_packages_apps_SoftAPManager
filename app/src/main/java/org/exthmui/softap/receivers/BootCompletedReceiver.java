package org.exthmui.softap.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import org.exthmui.softap.SoftApManageService;

public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, SoftApManageService.class);
            context.startServiceAsUser(serviceIntent, UserHandle.CURRENT_OR_SELF);
        }
    }
}