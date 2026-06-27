package com.qiujunawa.clientcontrol.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"))
    private void captureScrollDelta(long window, double horizontal, double vertical, CallbackInfo ci) {
        // 把滚轮增量传给录制系统
        // vertical > 0 向上滚，< 0 向下滚
        com.qiujunawa.clientcontrol.client.ClientControlClient.INSTANCE.addScrollDelta(vertical);
    }
}