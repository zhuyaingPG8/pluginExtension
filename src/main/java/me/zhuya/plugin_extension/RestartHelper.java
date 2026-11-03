package me.zhuya.plugin_extension;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class RestartHelper {
	private static final long WAIT_TIMEOUT_MINUTES = 5;
	private static final long SETTLE_MILLIS = 1500;

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			log("no command to relaunch, aborting");
			return;
		}
		long pid = Long.parseLong(args[0]);
		String[] command = Arrays.copyOfRange(args, 1, args.length);
		if (!awaitExit(pid)) {
			// The old instance is still alive after the timeout. Launching anyway would open a second
			// MCreator on the same workspace (locking/corruption risk), so give up instead.
			log("MCreator pid " + pid + " did not exit within " + WAIT_TIMEOUT_MINUTES
					+ " minutes, not relaunching");
			return;
		}
		Thread.sleep(SETTLE_MILLIS);
		new ProcessBuilder(command).start();
	}

	private static boolean awaitExit(long pid) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(WAIT_TIMEOUT_MINUTES);
		while (System.nanoTime() < deadline) {
			if (!isAlive(pid))
				return true;
			Thread.sleep(250);
		}
		return !isAlive(pid);
	}

	private static boolean isAlive(long pid) {
		try {
			return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		} catch (Exception e) {
			return false;
		}
	}

	// This helper runs with only the plugin archive on its classpath, so log4j (a host-provided
	// dependency) is not available here; plain stderr keeps the helper dependency free.
	private static void log(String message) {
		System.err.println("[plugin_extension restart helper] " + message);
	}
}
