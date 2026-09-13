/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.network;

import com.viaversion.viafabricplus.ViaFabricPlus;
import net.fabricmc.loader.api.FabricLoader;

public final class ViaFabricPlusCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("viafabricplus");

    private ViaFabricPlusCompat() {}

    public static boolean uses1214SprintRules() {
        if (!LOADED) return false;

        try {
            return ViaFabricPlus.getImpl().getTargetVersion().getVersion() == 769;
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }
}
