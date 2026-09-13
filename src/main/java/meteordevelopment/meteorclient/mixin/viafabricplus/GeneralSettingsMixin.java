/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.viafabricplus;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.viaversion.viafabricplus.api.settings.impl.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.viaversion.viafabricplus.settings.impl.GeneralSettingsImpl", remap = false)
public abstract class GeneralSettingsMixin {
    // Only the multiplayer-screen default, not the add-server or direct-connect defaults.
    @ModifyExpressionValue(method = "<init>", at = @At(value = "FIELD", target = "Lcom/viaversion/viafabricplus/api/settings/impl/Orientation;RIGHT_TOP:Lcom/viaversion/viafabricplus/api/settings/impl/Orientation;", ordinal = 0), remap = false)
    private Orientation modifyDefaultPosition(Orientation original) {
        return Orientation.RIGHT_BOTTOM;
    }
}
