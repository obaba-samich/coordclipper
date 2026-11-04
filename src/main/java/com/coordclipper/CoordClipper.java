package com.coordclipper;

import com.coordclipper.commands.CoordClipCommand;
import com.coordclipper.modules.CoordClipSettings;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class CoordClipper extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Coord Clipper");

        Modules.get().add(new CoordClipSettings());
        Commands.add(new CoordClipCommand());
    }

    @Override
    public String getPackage() {
        return "com.coordclipper";
    }
}
