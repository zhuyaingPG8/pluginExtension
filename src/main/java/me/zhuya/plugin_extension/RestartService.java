package me.zhuya.plugin_extension;

import net.mcreator.ui.MCreatorApplication;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

final class RestartService {
	private static final Logger LOG = LogManager.getLogger("pluginExtension");

	private static MCreatorApplication application;
	private static File archive;
	private static volatile boolean armed;

	static void setApplication(MCreatorApplication mcreatorApplication) {
		application = mcreatorApplication;
	}
	static void setArchive(File pluginArchive) {
		archive = pluginArchive;
	}
	static boolean canRestart() {
		return application != null && archive != null && archive.isFile() && relaunchCommand() != null;
	}
	static void closeApplication() {
		application.closeApplication();
	}
	static void arm() {
		if (armed) return;
		armed = true;
		Runtime.getRuntime().addShutdownHook(new Thread(RestartService::spawnHelper, "plugin-groups-restart"));
	}
	static void disarm() {
		armed = false;
	}

	private static void spawnHelper() {
		String[] command = relaunchCommand();
		if (!armed || command == null || archive == null) return;
		try {
			List<String> helper = new ArrayList<>();
			helper.add(javaExecutable());
			helper.add("-cp");
			helper.add(archive.getAbsolutePath());
			helper.add(RestartHelper.class.getName());
			helper.add(Long.toString(ProcessHandle.current().pid()));
			helper.addAll(List.of(command));
			new ProcessBuilder(helper).redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			LOG.error("Could not spawn the restart helper; MCreator will not be relaunched automatically", e);
		}
	}

	private static String[] relaunchCommand() {
		try {
			String executable = ProcessHandle.current().info().command().orElse(null);
			if (executable == null) return null;
			if (!isJavaBinary(executable)) return new String[] { executable };
			List<String> command = new ArrayList<>();
			command.add(executable);
			command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
			command.add("-cp");
			command.add(System.getProperty("java.class.path"));
			command.add(mainClass());
			return command.toArray(String[]::new);
		} catch (Exception e) {
			LOG.warn("Could not determine the MCreator relaunch command", e);
			return null;
		}
	}

	private static boolean isJavaBinary(String executable) {
		String name = new File(executable).getName().toLowerCase();
		return name.equals("java") || name.equals("java.exe") || name.equals("javaw") || name.equals("javaw.exe");
	}

	private static String mainClass() {
		String command = System.getProperty("sun.java.command", "net.mcreator.Launcher");
		return command.split("\\s+")[0];
	}

	private static String javaExecutable() {
		File java = new File(new File(System.getProperty("java.home"), "bin"),
				System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
		return java.getAbsolutePath();
	}
}
