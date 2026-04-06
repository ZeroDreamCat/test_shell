package io.github.vvb2060.puellamagi;

import static io.github.vvb2060.puellamagi.App.TAG;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import io.github.vvb2060.puellamagi.databinding.ActivityMainBinding;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private Shell shell;
    private ActivityMainBinding binding;
    private final List<String> console = new AppendCallbackList();
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            console.add(getString(R.string.service_connected));
            App.server = IRemoteService.Stub.asInterface(binder);
            Shell.enableVerboseLogging = BuildConfig.DEBUG;
            shell = Shell.Builder.create().setFlags(Shell.FLAG_NON_ROOT_SHELL).build();
            check();
            getRunningAppProcesses();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            App.server = null;
            console.add(getString(R.string.service_disconnected));
        }
    };

    private boolean bind() {
        try {
            return bindIsolatedService(
                    new Intent(this, MagicaService.class),
                    Context.BIND_AUTO_CREATE,
                    "magica",
                    getMainExecutor(),
                    connection
            );
        } catch (Exception e) {
            Log.e(TAG, "Can not bind service", e);
            return false;
        }
    }

    void getRunningAppProcesses() {
        try {
            var processes = App.server.getRunningAppProcesses();
            console.add("uid pid processName pkgList importance");
            for (var process : processes) {
                var str = String.format(Locale.ROOT, "%d %d %s %s %d",
                        process.uid, process.pid, process.processName,
                        Arrays.toString(process.pkgList), process.importance);
                console.add(str);
            }
        } catch (RemoteException | SecurityException e) {
            console.add(Log.getStackTraceString(e));
        }
    }

    void cmd(String... cmds) {
        shell.newJob().add(cmds).to(console).submit(out -> {
            if (!out.isSuccess()) {
                console.add(Arrays.toString(cmds) + getString(R.string.exec_failed));
            }
        });
    }

    void check() {
        cmd("id");
        if (shell.isRoot()) {
            console.add(getString(R.string.root_shell_opened));
        } else {
            console.add(getString(R.string.cannot_open_root_shell));
            return;
        }

        var cmd = "ps -A 2>/dev/null | grep magiskd | grep -qv grep";
        var magiskd = ShellUtils.fastCmdResult(shell, cmd);
        if (magiskd) {
            console.add(getString(R.string.magiskd_running));
            killMagiskd();
        } else {
            console.add(getString(R.string.magiskd_not_running));
            installMagisk();
        }
    }


    @SuppressLint("SetTextI18n")
    void killMagiskd() {
        binding.install.setOnClickListener(v -> {
            var cmd = "kill -9 $(pidof magiskd)";
            if (ShellUtils.fastCmdResult(shell, cmd)) {
                console.add(getString(R.string.magiskd_killed));
            } else {
                console.add(getString(R.string.magiskd_failed_to_kill));
            }
            binding.install.setEnabled(false);
        });
        binding.install.setText("Kill magiskd");
        binding.install.setVisibility(View.VISIBLE);
    }

	@SuppressLint("SetTextI18n")
	void installMagisk() {
		String cmdFilePath = "/sdcard/my_commands.txt";

		// 先检查 shell 是否有效
		if (shell == null) {
			console.add("Shell 对象为 null，尚未初始化");
			return;
		}
		console.add("Shell 状态: " + (shell.isRoot() ? "Root" : "Non-root"));

		// 执行检查命令，并获取详细输出
		String checkCmd = "test -f " + cmdFilePath + " && echo EXISTS || echo NOT_EXISTS";
		Shell.Result checkResult = shell.newJob().add(checkCmd).exec();

		// 输出详细信息
		console.add("检查命令退出码: " + checkResult.getCode());
		console.add("检查命令 stdout: " + checkResult.getOut());
		console.add("检查命令 stderr: " + checkResult.getErr());

		// 如果退出码不是 0，说明命令执行失败（可能是 shell 问题或文件不存在）
		if (checkResult.getCode() != 0) {
			console.add("检查命令执行失败，退出码非0");
			// 继续打印 stderr 中的错误信息（如果有）
			if (!checkResult.getErr().isEmpty()) {
				for (String err : checkResult.getErr()) {
					console.add("stderr: " + err);
				}
			}
			return;
		}

		// 通过 stdout 判断文件是否存在
		boolean exists = false;
		for (String line : checkResult.getOut()) {
			if (line.contains("EXISTS")) {
				exists = true;
				break;
			}
		}

		if (!exists) {
			console.add("命令文件不存在: " + cmdFilePath);
			// 可选：尝试列出 /sdcard 目录内容以供调试
			Shell.Result lsResult = shell.newJob().add("ls -la /sdcard/").exec();
			console.add("/sdcard/ 目录内容:");
			for (String line : lsResult.getOut()) {
				console.add(line);
			}
			return;
		}

		// 读取文件内容，每行一条命令
		Shell.Result readResult = shell.newJob().add("cat " + cmdFilePath).exec();
		if (readResult.getCode() != 0) {
			console.add("读取命令文件失败，退出码: " + readResult.getCode());
			for (String err : readResult.getErr()) {
				console.add("stderr: " + err);
			}
			return;
		}

		List<String> commands = new ArrayList<>();
		for (String line : readResult.getOut()) {
			line = line.trim();
			if (!line.isEmpty() && !line.startsWith("#")) {
				commands.add(line);
			}
		}

		if (commands.isEmpty()) {
			console.add("命令文件为空，没有可执行的命令");
			return;
		}

		console.add("找到 " + commands.size() + " 条命令，点击下方按钮执行");
		binding.install.setText("执行自定义命令");
		binding.install.setVisibility(View.VISIBLE);
		binding.install.setOnClickListener(v -> {
			binding.install.setEnabled(false);
			console.add(">>> 开始执行自定义命令 <<<");
			shell.newJob().add(commands.toArray(new String[0])).to(console).submit(result -> {
				if (result.isSuccess()) {
					console.add(">>> 所有命令执行成功 <<<");
				} else {
					console.add(">>> 部分命令执行失败，退出码：" + result.getCode() + " <<<");
				}
				binding.install.setEnabled(true);
			});
		});
	}

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        console.add(getString(R.string.start_service, Boolean.toString(bind())));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(connection);
    }

    class AppendCallbackList extends CallbackList<String> {
        @Override
        public void onAddElement(String s) {
            binding.console.append(s);
            binding.console.append("\n");
            binding.sv.postDelayed(() -> binding.sv.fullScroll(ScrollView.FOCUS_DOWN), 10);
        }
    }
}
