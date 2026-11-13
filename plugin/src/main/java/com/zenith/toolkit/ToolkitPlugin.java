package com.zenith.toolkit;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenith.toolkit.module.ScoreboardDeathLogger;
import com.zenith.toolkit.module.SessionLogger;
import com.zenith.toolkit.module.ChatStatsBot;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Server monitoring toolkit: scoreboard logging, session tracking & chat relay",
    url = "https://github.com/kolsuzmuz/zenith-server-toolkit",
    authors = {"ZenithToolkit"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class ToolkitPlugin implements ZenithProxyPlugin {
    public static ToolkitConfig CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("ZenithServerToolkit loading...");
        CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, ToolkitConfig.class);
        pluginAPI.registerModule(new ScoreboardDeathLogger());
        pluginAPI.registerModule(new SessionLogger());
        pluginAPI.registerModule(new ChatStatsBot());
        LOG.info("ZenithServerToolkit loaded!");
    }
}
