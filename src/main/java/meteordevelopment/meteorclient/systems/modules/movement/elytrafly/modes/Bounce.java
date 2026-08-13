/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

/*
 * Credit to Luna (https://github.com/InLieuOfLuna) for making the original Elytra Recast mod (https://github.com/InLieuOfLuna/elytra-recast)!
 */

package meteordevelopment.meteorclient.systems.modules.movement.elytrafly.modes;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
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

    private boolean rubberbanded;
    private boolean hasStartedFlying;

    private int tickDelay = elytraFly.restartDelay.get();
    private double prevFov;

    @Override
    public void onTick() {
        super.onTick();

        // Make sure all the conditions are met (player has an elytra, isn't in water, etc)
        if (checkConditions(mc.player)) {
            if (!rubberbanded) {
                if (prevFov != 0 && !elytraFly.sprint.get())
                    mc.options.fovEffectScale().set(0.0); // This stops the FOV effects from constantly going on and off.
                if (elytraFly.autoJump.get()) mc.options.keyJump.setDown(true);
                mc.options.keyUp.setDown(true);
                mc.player.setYRot(getYawDirection());
                if (elytraFly.lockPitch.get()) mc.player.setXRot(elytraFly.pitch.get().floatValue());
            }

            if (!elytraFly.sprint.get()) {
                // Sprinting all the time (when not on ground) makes it rubberband on certain anticheats.
                if (mc.player.isFallFlying()) mc.player.setSprinting(mc.player.onGround());
                else mc.player.setSprinting(true);
            }

            // Rubberbanding
            if (rubberbanded && elytraFly.restart.get()) {
                if (tickDelay > 0) {
                    tickDelay--;
                } else {
                    rubberbanded = false;
                    tickDelay = elytraFly.restartDelay.get();
                }
            }
        }
    }

    @Override
    public void onPreTick() {
        super.onPreTick();

        if (!checkConditions(mc.player)) return;

        if (elytraFly.sprint.get()) mc.player.setSprinting(true);

        // Re-deploy before physics, but only after a real airborne frame. Recasting while grounded can leave the
        // client gliding after the server rejected the packet, which causes repeated setbacks on strict anticheats.
        boolean flying = mc.player.isFallFlying();
        if (flying) {
            hasStartedFlying = true;
        } else if (!rubberbanded && !mc.player.onGround() && (!elytraFly.manualTakeoff.get() || hasStartedFlying)) {
            recastElytra(mc.player);
        }
    }

    private void unpress() {
        mc.options.keyUp.setDown(false);
        if (elytraFly.autoJump.get()) mc.options.keyJump.setDown(false);
    }

    @Override
    public void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            rubberbanded = true;
            tickDelay = elytraFly.restartDelay.get();
            unpress();
            mc.player.stopFallFlying();
        }
    }

    @Override
    public void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundPlayerCommandPacket playerCommandPacket && playerCommandPacket.getAction().equals(ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) && !elytraFly.sprint.get()) {
            mc.player.setSprinting(true);
        }
    }

    public static boolean recastElytra(LocalPlayer player) {
        if (!player.onGround() && checkConditions(player) && startGliding(player)) {
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return true;
        } else return false;
    }

    public static boolean checkConditions(LocalPlayer player) {
        BlockState blockState = player.getInBlockState();
        boolean isClimbing = (blockState.is(BlockTags.CLIMBABLE) && !blockState.is(BlockTags.CAN_GLIDE_THROUGH));
        return (!player.getAbilities().flying && !player.isPassenger() && !isClimbing && !player.isInWater() && !player.hasEffect(MobEffects.LEVITATION));
    }

    private static boolean startGliding(LocalPlayer player) {
        for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
            if (LivingEntity.canGlideUsing(player.getItemBySlot(equipmentSlot), equipmentSlot)) {
                MeteorClient.mc.executeIfPossible(player::startFallFlying);
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

        rubberbanded = false;
        hasStartedFlying = false;
        tickDelay = elytraFly.restartDelay.get();
        prevFov = mc.options.fovEffectScale().get();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();

        unpress();
        rubberbanded = false;
        hasStartedFlying = false;
        if (prevFov != 0 && !elytraFly.sprint.get()) mc.options.fovEffectScale().set(prevFov);
    }
}
