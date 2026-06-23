package com.qiujunawa.clientcontrol.mixin;

import com.qiujunawa.clientcontrol.client.ClientControlClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    @ModifyVariable(
            method = "tickMovement",
            at = @At(value = "STORE", ordinal = 0),
            ordinal = 0
    )
    private float modifySpeed(float original) {
        float multiplier = ClientControlClient.getSpeedMultiplier();
        System.out.println("[Mixin] 原始速度: " + original + ", 倍率: " + multiplier);
        if (multiplier < 1.0f && multiplier > 0.0f) {
            return original * multiplier;
        }
        return original;
    }
}