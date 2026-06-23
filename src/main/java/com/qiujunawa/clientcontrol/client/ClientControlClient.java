package com.qiujunawa.clientcontrol.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.entity.Entity;

import org.lwjgl.glfw.GLFW;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

public class ClientControlClient implements ClientModInitializer {

    private static ClientControlClient INSTANCE;
    private MinecraftClient client;

    // 自动行走任务
    private AutoWalkTask currentWalkTask = null;

    // 速度倍率
    private float speedMultiplier = 1.0f;

    // 当通过快捷键或指令激活自动行走时，短时间内不要把激活键/回车判定为玩家接管。
    // 直到所有相关键被松开后，才开始识别手动接管。
    private volatile boolean suppressManualUntilKeysReleased = false;
    private volatile ScheduledFuture<?> pendingExecution = null;

    // Getter for mixin and other classes
    public static float getSpeedMultiplier() {
        return INSTANCE == null ? 1.0f : INSTANCE.speedMultiplier;
    }

    // 快捷键
    private static KeyBinding toggleWalkKey;

    // 延迟执行调度器（用于在不阻塞游戏主线程的情况下延迟执行命令）
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ClientControl-Scheduler");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        client = MinecraftClient.getInstance();

        registerCommands();
        registerHotkeys();
        registerTickHandler();

        System.out.println("[ClientControl] 模组加载成功！");
    }

    // ==================== 指令注册 ====================
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // ---------- /ccwalk ----------
            dispatcher.register(ClientCommandManager.literal("ccwalk")
                    .then(ClientCommandManager.argument("direction", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                builder.suggest("toward");
                                builder.suggest("backward");
                                builder.suggest("left");
                                builder.suggest("right");
                                return builder.buildFuture();
                            })
                            .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        builder.suggest("hold");
                                        builder.suggest("press");
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String dir = StringArgumentType.getString(ctx, "direction");
                                        String mode = StringArgumentType.getString(ctx, "mode");
                                        executeWalk(dir, mode, mode.equals("press") ? 1 : -1);
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer())
                                            .executes(ctx -> {
                                                String dir = StringArgumentType.getString(ctx, "direction");
                                                String mode = StringArgumentType.getString(ctx, "mode");
                                                int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                                executeWalk(dir, mode, ticks);
                                                return 1;
                                            })
                                    )
                            )
                    )
            );

            // ---------- /ccsetspeed ----------
            // 在命令处理处加入 player null 检查示例（/ccsetspeed 和 /ccgetid）
            dispatcher.register(ClientCommandManager.literal("ccsetspeed")
                    .then(ClientCommandManager.argument("multiplier", FloatArgumentType.floatArg(0, 1))
                            .executes(ctx -> {
                                speedMultiplier = FloatArgumentType.getFloat(ctx, "multiplier");
                                if (client.player != null) {
                                    client.player.sendMessage(Text.literal("§a速度已设置为 " + (int)(speedMultiplier * 100) + "%"), false);
                                } else {
                                    System.out.println("[ClientControl] 速度已设置为 " + speedMultiplier + "，但玩家为 null 无法发送消息。");
                                }
                                return 1;
                            })
                    )
            );

            dispatcher.register(ClientCommandManager.literal("ccgetid")
                    .executes(ctx -> {
                        if (client.player == null) {
                            System.out.println("[ClientControl] 无玩家，无法执行 ccgetid");
                            return 1;
                        }
                        if (client.crosshairTarget instanceof EntityHitResult) {
                            EntityHitResult hit = (EntityHitResult) client.crosshairTarget;
                            Entity e = hit.getEntity();
                            client.player.sendMessage(Text.literal(
                                    "§e实体: §f" + e.getName().getString() +
                                            " §7| ID: §f" + e.getId() +
                                            " §7| UUID: §f" + e.getUuidAsString()), false);
                        } else {
                            client.player.sendMessage(Text.literal("§c请对准一个实体"), false);
                        }
                        return 1;
                    })
            );
        });
    }

    // ==================== 快捷键 ====================
    private void registerHotkeys() {
        toggleWalkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.clientcontrol.toggle_walk",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.clientcontrol"
        ));
    }

    // ==================== Tick ====================
    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (currentWalkTask != null) currentWalkTask.tick();

            // 如果当前处于 suppression 模式，等待所有相关按键被松开才开始识别接管
            if (currentWalkTask != null) {
                if (suppressManualUntilKeysReleased) {
                    boolean anyPressedNow = false;
                    try {
                        if (client.options.forwardKey != null && client.options.forwardKey.isPressed()) anyPressedNow = true;
                        if (client.options.backKey != null && client.options.backKey.isPressed()) anyPressedNow = true;
                        if (client.options.leftKey != null && client.options.leftKey.isPressed()) anyPressedNow = true;
                        if (client.options.rightKey != null && client.options.rightKey.isPressed()) anyPressedNow = true;
                        if (client.options.jumpKey != null && client.options.jumpKey.isPressed()) anyPressedNow = true;
                        if (client.options.sneakKey != null && client.options.sneakKey.isPressed()) anyPressedNow = true;
                        if (toggleWalkKey != null && toggleWalkKey.isPressed()) anyPressedNow = true;
                    } catch (Exception e) {
                        anyPressedNow = false;
                    }
                    if (!anyPressedNow) {
                        // 所有按键松开，解除 suppression，从下一次按键开始识别接管
                        suppressManualUntilKeysReleased = false;
                    }
                } else {
                    // 正常识别接管：短按或长按都算
                    boolean manualPress = false;
                    try {
                        if (client.options.forwardKey != null && (client.options.forwardKey.wasPressed() || client.options.forwardKey.isPressed())) manualPress = true;
                        if (client.options.backKey != null && (client.options.backKey.wasPressed() || client.options.backKey.isPressed())) manualPress = true;
                        if (client.options.leftKey != null && (client.options.leftKey.wasPressed() || client.options.leftKey.isPressed())) manualPress = true;
                        if (client.options.rightKey != null && (client.options.rightKey.wasPressed() || client.options.rightKey.isPressed())) manualPress = true;
                        if (client.options.jumpKey != null && (client.options.jumpKey.wasPressed() || client.options.jumpKey.isPressed())) manualPress = true;
                        if (client.options.sneakKey != null && (client.options.sneakKey.wasPressed() || client.options.sneakKey.isPressed())) manualPress = true;
                        // 如果你希望将其他快捷键（非 toggleWalkKey）也识别为接管，可在此加入
                    } catch (Exception e) {
                        manualPress = false;
                    }

                    if (manualPress) {
                        // 如果有待执行的延迟任务，取消它
                        if (pendingExecution != null) {
                            try { pendingExecution.cancel(false); } catch (Exception ex) { }
                            pendingExecution = null;
                        }

                        currentWalkTask.stop();
                        suppressManualUntilKeysReleased = false;
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§c检测到玩家接管，自动行走已停止"), false);
                        }
                    }
                }
            }

            if (toggleWalkKey.wasPressed()) {
                if (currentWalkTask != null && currentWalkTask.isActive()) {
                    // 用户按下 toggle 停止，这不应被识别为“接管”场景
                    currentWalkTask.stop();
                    suppressManualUntilKeysReleased = false;
                    if (client.player != null) client.player.sendMessage(Text.literal("§c自动行走已关闭"), false);
                } else {
                    // 启动自动行走，并抑制接管识别直到所有键松开
                    suppressManualUntilKeysReleased = true;
                    executeWalk("toward", "hold", -1);
                }
            }
        });
    }

    // ==================== 执行逻辑 ====================
    // 修改 executeWalk 中 press 分支以及增加 player 空检查
    private void executeWalk(String direction, String mode, int ticks) {
        if (client == null) {
            System.out.println("[ClientControl] client is null");
            return;
        }
        if (client.player == null) {
            System.out.println("[ClientControl] 没有玩家！");
            return;
        }

        WalkDir dir = WalkDir.fromString(direction);
        if (dir == null) {
            client.player.sendMessage(Text.literal("§c无效方向"), false);
            System.out.println("[ClientControl] 无效方向！");
            return;
        }

        // 给玩家一点反馈，表示命令已接收并将在 0.5 秒后执行
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§e命令已接收，将在 0.5 秒后执行..."), false);
        }

        // 在单独的调度线程中等待 500ms，然后回到客户端线程执行真正的逻辑（不会暂停游戏）
        ScheduledFuture<?> future = SCHEDULER.schedule(() -> {
            // 切回到客户端主线程执行与游戏状态交互的代码
            if (client == null) return;
            client.execute(() -> {
                // 延迟开始执行前再检查 player 是否存在
                if (client.player == null) {
                    System.out.println("[ClientControl] 玩家在等待期间丢失，取消执行。");
                    return;
                }
                // 执行时也不应当将当前按键（用于触发指令/快捷键）误判为接管。
                // 我们仍然依赖 suppressManualUntilKeysReleased 来在按键释放后开始识别。
                if (mode.equalsIgnoreCase("press")) {
                    // 统一停止已有任务，避免重叠
                    if (currentWalkTask != null) {
                        currentWalkTask.stop();
                    }
                    // 使用一个持续 1 tick 的任务来模拟“按一下”
                    currentWalkTask = new AutoWalkTask(dir, 1);
                    client.player.sendMessage(Text.literal("§a已执行 " + dir.name + " 一次"), false);
                    System.out.println("[ClientControl] 执行按压（1 tick）！");
                } else {
                    if (currentWalkTask != null) {
                        System.out.println("[ClientControl] 停止旧任务！");
                        currentWalkTask.stop();
                    }
                    currentWalkTask = new AutoWalkTask(dir, ticks <= 0 ? -1 : ticks);
                    String timeDesc = ticks <= 0 ? "永久" : (ticks / 20.0 + "秒");
                    client.player.sendMessage(Text.literal("§a已开启 " + dir.name + " 自动行走，时长: " + timeDesc), false);
                    System.out.println("[ClientControl] 执行按住！");
                }
                // 已经执行，清除 pendingExecution
                pendingExecution = null;
            });
        }, 500, TimeUnit.MILLISECONDS);

        // 记录以便在玩家接管时可以取消
        pendingExecution = future;
    }

    // ==================== 方向枚举 ====================
    enum WalkDir {
        TOWARD("向前") {
            void setPressed(MinecraftClient c, boolean p) {
                if (c.options.forwardKey != null) c.options.forwardKey.setPressed(p);
            }
        },
        BACKWARD("向后") {
            void setPressed(MinecraftClient c, boolean p) {
                if (c.options.backKey != null) c.options.backKey.setPressed(p);
            }
        },
        LEFT("向左") {
            void setPressed(MinecraftClient c, boolean p) {
                if (c.options.leftKey != null) c.options.leftKey.setPressed(p);
            }
        },
        RIGHT("向右") {
            void setPressed(MinecraftClient c, boolean p) {
                if (c.options.rightKey != null) c.options.rightKey.setPressed(p);
            }
        };

        final String name;
        WalkDir(String name) { this.name = name; }
        abstract void setPressed(MinecraftClient client, boolean pressed);
        static WalkDir fromString(String s) {
            for (WalkDir d : values()) {
                if (d.name().equalsIgnoreCase(s)) return d;
            }
            return null;
        }
    }

    // ==================== 自动行走任务 ====================
    // 修改 AutoWalkTask，stop() 中清理 currentWalkTask，去掉 isActive 中频繁日志
    class AutoWalkTask {
        private final WalkDir dir;
        private long remainingTicks;

        AutoWalkTask(WalkDir dir, long ticks) {
            this.dir = dir;
            this.remainingTicks = ticks;
            dir.setPressed(client, true);
            System.out.println("[ClientControl] 按下！");
        }

        void tick() {
            if (remainingTicks > 0) {
                if (--remainingTicks == 0) stop();
            }
        }

        void stop() {
            dir.setPressed(client, false);
            System.out.println("[ClientControl] 松开！");
            remainingTicks = 0;
            // 取消任何待执行的延迟任务
            if (pendingExecution != null) {
                try { pendingExecution.cancel(false); } catch (Exception e) { }
                pendingExecution = null;
            }
            // 清理外部引用以便垃圾回收，并让逻辑更清晰
            currentWalkTask = null;
            // 解除 suppression（已停止，允许正常识别按键）
            suppressManualUntilKeysReleased = false;
        }

        boolean isActive() {
            return remainingTicks != 0;
        }
    }
}
