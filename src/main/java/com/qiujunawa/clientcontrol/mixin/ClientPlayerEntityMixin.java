package com.qiujunawa.clientcontrol.mixin;

import com.qiujunawa.clientcontrol.client.ClientControlClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

	@Inject(method = "tickMovement", at = @At("HEAD"))
	private void onTickMovement(CallbackInfo ci) {
		float multiplier = ClientControlClient.getSpeedMultiplier();
		if (multiplier < 1.0f && multiplier > 0.0f) {
			ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
			player.setVelocity(player.getVelocity().multiply(multiplier));
			System.out.println("[Mixin] 速度已修改，倍率: " + multiplier);
		}
	}
}
