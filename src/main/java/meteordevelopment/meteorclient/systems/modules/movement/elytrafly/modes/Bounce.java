/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

/*
 * Credit to Luna (https://github.com/InLieuOfLuna) for making the original Elytra Recast mod (https://github.com/InLieuOfLuna/elytra-recast)!
 */

package meteordevelopment.meteorclient.systems.modules.movement.elytrafly.modes;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.utils.network.ViaFabricPlusCompat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

public class Bounce extends ElytraFlightMode {

    public Bounce() {
        super(ElytraFlightModes.Bounce);
    }

    private boolean hasStartedFlying;
    private boolean forcingJump;
    private boolean forcingForward;
    private boolean setbackPaused;
    private long glideRetryTick;

    private int restartTicks;
    private double prevFov;

    @Override
    public void onPreTick() {
        super.onPreTick();

        if (!checkConditions(mc.player) || !hasUsableGlider(mc.player)) {
            unpress();
            return;
        }

        boolean flying = mc.player.isFallFlying();
        if (flying) hasStartedFlying = true;

        if (setbackPaused) {
            if (!elytraFly.restart.get()) {
                unpress();
                return;
            }

            if (restartTicks > 0) {
                restartTicks--;
                unpress();
                return;
            }

            setbackPaused = false;
        }

        if (prevFov != 0 && !elytraFly.sprint.get()) mc.options.fovEffectScale().set(0.0);

        forcingForward = true;
        mc.options.keyUp.setDown(true);
        mc.player.setYRot(getYawDirection());
        if (elytraFly.lockPitch.get()) mc.player.setXRot(elytraFly.pitch.get().floatValue());

        if (elytraFly.sprint.get()) mc.player.setSprinting(true);

        if (elytraFly.manualTakeoff.get() && !hasStartedFlying) {
            setJump(false);
            return;
        }

        // Since 1.21.2, vanilla sends the glide action before the new input packet. Grim expects the previous
        // input to have jump released and the following input to contain a fresh jump press.
        boolean canRequest = !ViaFabricPlusCompat.uses1214SprintRules() || mc.player.tickCount >= glideRetryTick;
        boolean shouldJump = elytraFly.autoJump.get() && (mc.player.onGround() || (!flying && !forcingJump && canRequest));
        setJump(shouldJump);

        if (!elytraFly.sprint.get()) {
            // Sprinting all the time (when not on ground) makes it rubberband on certain anticheats.
            mc.player.setSprinting(!flying || mc.player.onGround());
        }
    }

    private void unpress() {
        glideRetryTick = 0;
        forcingForward = false;
        mc.options.keyUp.setDown(false);
        setJump(false);
    }

    public void restoreInput() {
        // Opening a screen releases keys after TickEvent.Pre but before player input is sampled.
        if (forcingForward) mc.options.keyUp.setDown(true);
        if (elytraFly.autoJump.get()) mc.options.keyJump.setDown(forcingJump);
    }

    public void prepareGroundJump() {
        if (forcingForward && forcingJump && !setbackPaused && checkConditions(mc.player) && hasUsableGlider(mc.player)) {
            // Apply after LocalPlayer's legacy sprint cancellation, before jump physics and
            // the normal sprint-state packet. A synthetic boost alone desynchronizes them.
            mc.player.setSprinting(true);
        }
    }

    public boolean shouldKeepSprinting() {
        // 1.21.4 cancels sprint inside aiStep, after our Pre tick. Honor the explicit
        // constant-sprint option across that reset, without changing VFP's vanilla rules.
        // Inspired by Sophie / ebonkfix's native 1.21.4 fix (8693f890d6b2b9f5d4a161367149b4a621d28be0):
        // https://github.com/sophimoo/ebonkfix/blob/8693f890d6b2b9f5d4a161367149b4a621d28be0/src/main/java/me/sophimoo/ebonkfix/mixin/EntityMixin.java
        return forcingForward && !setbackPaused && elytraFly.sprint.get() && ViaFabricPlusCompat.uses1214SprintRules()
            && mc.player != null && mc.player.isAlive() && checkConditions(mc.player) && hasUsableGlider(mc.player);
    }

    private void setJump(boolean pressed) {
        if (elytraFly.autoJump.get() || forcingJump) {
            mc.options.keyJump.setDown(pressed);
            forcingJump = pressed;
        }
    }

    public void onPositionApplied() {
        // PacketEvent.Receive runs on Netty. Keep recovery and input state on the
        // game thread, after the authoritative position has actually been applied.
        setbackPaused = true;
        restartTicks = elytraFly.restartDelay.get();
        unpress();
    }

    @Override
    public void onPacketSend(PacketEvent.Send event) {
        if (!mc.isSameThread()) return;
        if (event.packet instanceof ServerboundPlayerCommandPacket playerCommandPacket && playerCommandPacket.getAction().equals(ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)) {
            if (!elytraFly.sprint.get()) mc.player.setSprinting(true);
            if (!event.isCancelled() && forcingForward && !setbackPaused && ViaFabricPlusCompat.uses1214SprintRules()) {
                final var info = mc.player.connection.getPlayerInfo(mc.player.getUUID());
                int latency = info == null ? 0 : Math.max(0, info.getLatency());
                // Wait for acknowledgement: one round trip plus four ticks, capped at one second.
                glideRetryTick = (long) mc.player.tickCount + Math.clamp((latency + 49L) / 50 + 4, 4, 20);
            }
        }
    }

    public void onMetadataApplied(ClientboundSetEntityDataPacket packet) {
        if (packet.id() != mc.player.getId()) return;
        for (var item : packet.packedItems()) {
            if (item.id() == 0 && item.value() instanceof Byte flags && (flags & 0x80) != 0) glideRetryTick = 0;
        }
    }

    public static boolean checkConditions(LocalPlayer player) {
        BlockState blockState = player.getInBlockState();
        boolean isClimbing = (blockState.is(BlockTags.CLIMBABLE) && !blockState.is(BlockTags.CAN_GLIDE_THROUGH));
        return (!player.getAbilities().flying && !player.isPassenger() && !isClimbing && !player.isInWater() && !player.hasEffect(MobEffects.LEVITATION));
    }

    private static boolean hasUsableGlider(LocalPlayer player) {
        for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
            if (LivingEntity.canGlideUsing(player.getItemBySlot(equipmentSlot), equipmentSlot)) {
                return true;
            }
        }

        return false;
    }

    private float getYawDirection() {
        return switch (elytraFly.yawLockMode.get()) {
            case None -> mc.player.getYRot();
            case Smart -> Math.round((mc.player.getYRot() + 1f) / 45f) * 45f;
            case Simple -> elytraFly.yaw.get().floatValue();
        };

    }

    @Override
    public void onActivate() {
        super.onActivate();

        glideRetryTick = 0;

        hasStartedFlying = mc.player.isFallFlying();
        forcingJump = false;
        setbackPaused = false;
        restartTicks = 0;
        prevFov = mc.options.fovEffectScale().get();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();

        unpress();
        hasStartedFlying = false;
        setbackPaused = false;
        restartTicks = 0;
        if (prevFov != 0 && !elytraFly.sprint.get()) mc.options.fovEffectScale().set(prevFov);
    }
}
