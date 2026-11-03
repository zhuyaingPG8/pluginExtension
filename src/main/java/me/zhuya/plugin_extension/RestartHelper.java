package me.zhuya.plugin_extension;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class RestartHelper {
	private static final long WAIT_TIMEOUT_MINUTES = 5;
	private static final long SETTLE_MILLIS = 1500;

	static void main(String[] args) throws Exception {
		if (args.length < 2)
			return;
		long pid = Long.parseLong(args[0]);
		String[] command = Arrays.copyOfRange(args, 1, args.length);
		awaitExit(pid);
		Thread.sleep(SETTLE_MILLIS);
		new ProcessBuilder(command).start();
	}

	private static void awaitExit(long pid) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(WAIT_TIMEOUT_MINUTES);
		while (System.nanoTime() < deadline) {
			if (!isAlive(pid))
				return;
			Thread.sleep(250);
		}
	}

	private static boolean isAlive(long pid) {
		try {
			return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		} catch (Exception e) {
			return false;
		}
	}
}
