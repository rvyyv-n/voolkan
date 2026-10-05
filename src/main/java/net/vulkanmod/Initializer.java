package net.vulkanmod;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * mod id lookup renamed to voolkan; update check disabled.
 */

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {
		VERSION = FabricLoader.getInstance()
				.getModContainer("voolkan")
				.get()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== voolkan (unofficial VulkanMod fork) ==");

		var configPath = FabricLoader.getInstance()
				.getConfigDir()
				.resolve("vulkanmod_settings.json");

		CONFIG = loadConfig(configPath);
		Platform.init();

		Renderer.register(VulkanModRenderer.INSTANCE);

		// Update check disabled in voolkan: upstream releases do not run on the target GPUs
		UpdateChecker.checkForUpdates();
	}

	private static Config loadConfig(Path path) {
        return Config.load(path);
	}

	public static String getVersion() {
		return VERSION;
	}
}
