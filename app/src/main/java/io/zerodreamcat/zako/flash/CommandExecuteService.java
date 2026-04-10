package io.zerodreamcat.zako.flash;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.List;

public class CommandExecuteService extends Service {
    private static final String TAG = "CmdExecService";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String cmd = intent.getStringExtra(CommandReceiver.EXTRA_CMD);
            if (cmd != null && !cmd.isEmpty()) {
                executeCommand(cmd);
            }
        }
        return START_NOT_STICKY;
    }

    private void executeCommand(String cmd) {
        StringBuilder output = new StringBuilder();

        if (App.rootShell == null) {
            output.append("Error: root shell not initialized. Please launch the app first.\n");
            sendResultAndLog(output.toString());
            stopSelf();
            return;
        }

        if (!App.rootShell.isRoot()) {
            output.append("Error: shell is not root.\n");
            sendResultAndLog(output.toString());
            stopSelf();
            return;
        }

        List<String> stdout = new ArrayList<>();
        // 关键：将 stderr 合并到 stdout
        Shell.Result result = App.rootShell.newJob().add(cmd + " 2>&1").to(stdout).exec();

        if (stdout.isEmpty() && result.getOut().isEmpty()) {
            output.append("(no output)\n");
        } else {
            for (String line : stdout) {
                output.append(line).append("\n");
            }
        }
        output.append("Exit code: ").append(result.getCode());
        sendResultAndLog(output.toString());
        stopSelf();
    }

    private void sendResultAndLog(String output) {
        Log.d(TAG, "Command result:\n" + output);
        Intent resultIntent = new Intent(CommandReceiver.ACTION_RESULT);
        resultIntent.putExtra(CommandReceiver.EXTRA_OUTPUT, output);
        sendBroadcast(resultIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
