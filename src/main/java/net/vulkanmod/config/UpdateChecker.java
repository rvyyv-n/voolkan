package net.vulkanmod.config;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * update check disabled (it pointed users to upstream VulkanMod releases, which do not run on the target GPUs).
 */

import net.vulkanmod.Initializer;

public abstract class UpdateChecker {
    private static final boolean updateAvailable = false;

    public static void checkForUpdates() {
        Initializer.LOGGER.info("Update check is disabled in voolkan.");
    }

    public static boolean isUpdateAvailable() {
        return updateAvailable;
    }
}
