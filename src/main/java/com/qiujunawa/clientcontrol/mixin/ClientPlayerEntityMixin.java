package com.qiujunawa.clientcontrol.mixin;

import com.qiujunawa.clientcontrol.client.ClientControlClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    @Redirect(
            method = "tickMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getAttributeValue(Lnet/minecraft/entity/attribute/EntityAttribute;)D")
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