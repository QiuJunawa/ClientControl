package com.qiujunawa.clientcontrol.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.entity.Entity;
import net.minecraft.client.Mouse;

import org.lwjgl.glfw.GLFW;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.util.InputUtil;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.lwjgl.glfw.GLFW;
import java.util.Arrays;

public class ClientControlClient implements ClientModInitializer {

    public static ClientControlClient INSTANCE;
    private MinecraftClient client;

    // 自动行走任务
    private AutoWalkTask currentWalkTask = null;

    //重要！！！当前录制版本：
    private static final int RECORDING_VERSION = 2;  // 当前录制版本

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
    private static KeyBinding speedUpKey;
    private static KeyBinding speedDownKey;
    private static KeyBinding toggleBulldozerKey;

    // ==================== 录制/回放 ====================
    private boolean isRecording = false;
    private boolean isPlaying = false;
    private BufferedWriter recordingWriter = null;
    private String currentRecordingName = "";
    private long recordingStartTime = 0;
    private long playbackStartTime = 0;
    private Queue<String> playbackQueue = new ConcurrentLinkedQueue<>();

    // 录制起始视角（用于计算相对视角）
    private float recordingStartYaw = 0, recordingStartPitch = 0;
    // 录制起始位置（用于计算相对位置）
    private double recordingStartX = 0, recordingStartY = 0, recordingStartZ = 0;
    // 回放起始视角（用于计算相对视角）
    private float playbackStartYaw = 0, playbackStartPitch = 0;
    // 回放起始位置（用于计算相对位置）
    private double playbackStartX = 0, playbackStartY = 0, playbackStartZ = 0;

    // 去重 - 按键状态（只跟踪我们关心的按键）
    private final java.util.Map<Integer, Boolean> lastKeyStates = new java.util.HashMap<>();
    // 去重 - 视角
    private float lastRecordedYaw = Float.NaN, lastRecordedPitch = Float.NaN;

    // 位置校准 - 上次校准时间
    private long lastCalibrationTime = 0;

    // 录制 flush 计数器（每 N tick 刷一次盘，减少磁盘 IO）
    private int flushCounter = 0;
    private static final int FLUSH_INTERVAL = 5; // 每 5 tick 刷一次盘

    // ==================== 录制/回放 ====================

    // 快捷栏状态
    private int recordingHotbarSlot = 0;
    private int playbackHotbarSlot = 0;

    // 滚轮状态（用于去重）
    private int lastRecordedScroll = 0;

    // 需要录制的按键列表（GLFW 按键码）
    private static final int[] RECORDED_KEYS = {
        // 移动
        GLFW.GLFW_KEY_W,
        GLFW.GLFW_KEY_A,
        GLFW.GLFW_KEY_S,
        GLFW.GLFW_KEY_D,
        // 跳跃
        GLFW.GLFW_KEY_SPACE,
        // 潜行（左右）
        GLFW.GLFW_KEY_LEFT_SHIFT,
        GLFW.GLFW_KEY_RIGHT_SHIFT,
        // 疾跑（左右Ctrl）
        GLFW.GLFW_KEY_LEFT_CONTROL,
        GLFW.GLFW_KEY_RIGHT_CONTROL,
        // 鼠标按键
        GLFW.GLFW_MOUSE_BUTTON_LEFT,
        GLFW.GLFW_MOUSE_BUTTON_RIGHT,
        // 数字键 1-9
        GLFW.GLFW_KEY_1,
        GLFW.GLFW_KEY_2,
        GLFW.GLFW_KEY_3,
        GLFW.GLFW_KEY_4,
        GLFW.GLFW_KEY_5,
        GLFW.GLFW_KEY_6,
        GLFW.GLFW_KEY_7,
        GLFW.GLFW_KEY_8,
        GLFW.GLFW_KEY_9,
        // 功能键
        GLFW.GLFW_KEY_E,   // 背包
        GLFW.GLFW_KEY_Q,   // 丢弃
        GLFW.GLFW_KEY_F    // 交换副手
    };
    // 滚轮增量（累积，录制后清零）
    private double accumulatedScrollDelta = 0;

    public void addScrollDelta(double delta) {
        this.accumulatedScrollDelta += delta;
    }

    public double getAndClearScrollDelta() {
        double delta = this.accumulatedScrollDelta;
        this.accumulatedScrollDelta = 0;
        return delta;
    }

    private File recordingDir = new File("config/clientcontrol/recordings/");


    // 延迟执行调度器（用于在不阻塞游戏主线程的情况下延迟执行命令）
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ClientControl-Scheduler");
        t.setDaemon(true);
        return t;
    });

    // 添加 getter
    public static ClientControlClient getInstance() {
        return INSTANCE;
    }

    // 创建配置界面（支持父界面）
    public Screen createConfigScreen(Screen parent) {
        return new ConfigScreen(parent ,null);
    }

    /**
     * 根据 GLFW 按键码模拟按键
     */
    private void simulateKeyByGLFWCode(int keyCode, boolean pressed) {
        if (client.options == null) return;

        // 映射 GLFW 按键码到 Minecraft 按键绑定
        // WASD
        if (keyCode == GLFW.GLFW_KEY_W) client.options.forwardKey.setPressed(pressed);
        else if (keyCode == GLFW.GLFW_KEY_S) client.options.backKey.setPressed(pressed);
        else if (keyCode == GLFW.GLFW_KEY_A) client.options.leftKey.setPressed(pressed);
        else if (keyCode == GLFW.GLFW_KEY_D) client.options.rightKey.setPressed(pressed);
            // 空格
        else if (keyCode == GLFW.GLFW_KEY_SPACE) client.options.jumpKey.setPressed(pressed);
            // Shift
        else if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT)
            client.options.sneakKey.setPressed(pressed);
            // Ctrl
        else if (keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL)
            client.options.sprintKey.setPressed(pressed);
            // 鼠标左键（特殊处理）
        else if (keyCode == GLFW.GLFW_MOUSE_BUTTON_LEFT)
            client.options.attackKey.setPressed(pressed);
            // 鼠标右键
        else if (keyCode == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            client.options.useKey.setPressed(pressed);
            // 数字键（热键栏）
        else if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int slot = keyCode - GLFW.GLFW_KEY_1;
            if (pressed && client.player != null) {
                try {
                    // 用反射设置 selectedSlot（绕过 private 访问限制）
                    java.lang.reflect.Field field = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
                    field.setAccessible(true);
                    field.setInt(client.player.getInventory(), slot);
                } catch (Exception e) {
                    System.err.println("[ClientControl] 设置物品栏失败: " + e.getMessage());
                }
            }
        }
        // E（打开背包）
        else if (keyCode == GLFW.GLFW_KEY_E) {
            client.options.inventoryKey.setPressed(pressed);
        }
        // Q（丢弃物品）
        else if (keyCode == GLFW.GLFW_KEY_Q) {
            client.options.dropKey.setPressed(pressed);
        }
        // F（交换副手）
        else if (keyCode == GLFW.GLFW_KEY_F) {
            client.options.swapHandsKey.setPressed(pressed);
        }
        // 其他按键可以继续添加...
    }

    // ==================== 配置界面 ====================

    // 确保调用新的配置界面
    private void openConfigScreen() {
        if (client == null) return;
        client.execute(() -> {
            client.setScreen(com.qiujunawa.clientcontrol.config.ClientControlConfig.createConfigScreen());
        });
    }

    private class ConfigScreen extends net.minecraft.client.gui.screen.Screen {
        private final net.minecraft.client.gui.screen.Screen parent;
        private final Runnable closeCallback;

        ConfigScreen(net.minecraft.client.gui.screen.Screen parent) {
            this(parent, null);
        }

        ConfigScreen(net.minecraft.client.gui.screen.Screen parent, Runnable closeCallback) {
            super(Text.literal("ClientControl 配置"));
            this.parent = parent;
            this.closeCallback = closeCallback;
        }

        @Override
        protected void init() {
            int centerX = this.width / 2;
            int baseY = this.height / 2 - 80;

            // ====== 寻路模式 ======
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§b寻路模式: §f" + config.retryMode.getDisplayName()),
                    button -> {
                        RetryPathMode[] modes = RetryPathMode.values();
                        int idx = (config.retryMode.ordinal() + 1) % modes.length;
                        config.retryMode = modes[idx];
                        config.save();
                        this.init();
                    }
            ).dimensions(centerX - 100, baseY, 200, 20).build());

            // ====== 最大重试次数 ======
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§b最大重试次数: §f" + config.maxRetries),
                    button -> {
                        config.maxRetries = config.maxRetries >= 10 ? 1 : config.maxRetries + 1;
                        config.save();
                        this.init();
                    }
            ).dimensions(centerX - 100, baseY + 30, 200, 20).build());

            // ====== 移动速度 ======
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§b移动速度: §f" + String.format("%.2f", config.moveSpeed)),
                    button -> {
                        config.moveSpeed += 0.05f;
                        if (config.moveSpeed > 1.0f) config.moveSpeed = 0.05f;
                        config.save();
                        this.init();
                    }
            ).dimensions(centerX - 100, baseY + 60, 200, 20).build());

            // ====== 重置默认 ======
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§c重置默认"),
                    button -> {
                        config.retryMode = RetryPathMode.STRAIGHT;
                        config.maxRetries = 3;
                        config.moveSpeed = 0.15f;
                        config.save();
                        this.init();
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§a配置已重置"), false);
                        }
                    }
            ).dimensions(centerX - 100, baseY + 100, 200, 20).build());

            // ====== 关闭按钮（放在右上角） ======
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("§e✕ 关闭"),
                    button -> this.close()
            ).dimensions(this.width - 70, 10, 60, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // 只调用一次 renderBackground
            //this.renderBackground(context, mouseX, mouseY, delta);

            int centerX = this.width / 2;

            // 标题
            String titleText = "§6§lClientControl";
            int titleWidth = this.textRenderer.getWidth(titleText);
            context.drawText(this.textRenderer, titleText,
                    centerX - titleWidth / 2, 30, 0xFFFFFF, true);

            // 副标题
            String subTitleText = "§7配置面板";
            int subTitleWidth = this.textRenderer.getWidth(subTitleText);
            context.drawText(this.textRenderer, subTitleText,
                    centerX - subTitleWidth / 2, 50, 0xCCCCCC, true);

            // 模式说明
            String descText = "§8" + config.retryMode.getDescription();
            int descWidth = this.textRenderer.getWidth(descText);
            context.drawText(this.textRenderer, descText,
                    centerX - descWidth / 2, this.height / 2 - 45, 0x999999, true);

            // 渲染按钮——用 super 绘制子组件
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            if (closeCallback != null) {
                closeCallback.run();
            }
            if (parent != null) {
                client.setScreen(parent);
            } else {
                super.close();
            }
        }
    }

    // ==================== 配置管理 ====================

    public enum RetryPathMode {
        STRAIGHT("直线", "直接直线冲向目标"),
        VILLAGER("村民", "使用村民AI寻路绕障"),
        RESTART("从头再来", "重置到录制起点重试，但除非你要跑酷，否则不推荐");

        private final String displayName;
        private final String description;

        RetryPathMode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    private static class Config {
        RetryPathMode retryMode = RetryPathMode.STRAIGHT;
        int maxRetries = 3;
        int retryWaitTicks = 10;
        double moveSpeed = 0.15;
        int calibrationRate = 1000; // 位置校准间隔（毫秒），0 = 关闭

        private static final File CONFIG_FILE = new File("config/clientcontrol/clientcontrol.txt");

        void save() {
            try {
                CONFIG_FILE.getParentFile().mkdirs();
                try (PrintWriter writer = new PrintWriter(new FileWriter(CONFIG_FILE))) {
                    writer.println("retryMode=" + retryMode.name());
                    writer.println("maxRetries=" + maxRetries);
                    writer.println("retryWaitTicks=" + retryWaitTicks);
                    writer.println("moveSpeed=" + moveSpeed);
                    writer.println("calibrationRate=" + calibrationRate);
                }
            } catch (Exception e) {}
        }

        void load() {
            try {
                if (!CONFIG_FILE.exists()) return;
                try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        String[] parts = line.split("=", 2);
                        if (parts.length < 2) continue;
                        switch (parts[0]) {
                            case "retryMode":
                                try { retryMode = RetryPathMode.valueOf(parts[1]); } catch (Exception e) {}
                                break;
                            case "maxRetries":
                                try { maxRetries = Integer.parseInt(parts[1]); } catch (Exception e) {}
                                break;
                            case "retryWaitTicks":
                                try { retryWaitTicks = Integer.parseInt(parts[1]); } catch (Exception e) {}
                                break;
                            case "moveSpeed":
                                try { moveSpeed = Double.parseDouble(parts[1]); } catch (Exception e) {}
                                break;
                            case "calibrationRate":
                                try { calibrationRate = Integer.parseInt(parts[1]); } catch (Exception e) {}
                                break;
                        }
                    }
                }
            } catch (Exception e) {}
        }
    }

    private Config config = new Config();

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        client = MinecraftClient.getInstance();

        config.load();
        com.qiujunawa.clientcontrol.config.ClientControlConfig.register();

        registerCommands();
        registerHotkeys();
        registerTickHandler();

        System.out.println("[ClientControl] 模组加载成功！");
    }    /**
     * 执行位置微调：相对于脚下方块的相对位置
     * ~ 表示保持当前值不变
     */
    private void executeTweak(String xStr, String yStr, String zStr, String yawStr, String pitchStr) {
        if (client.player == null) {
            System.out.println("[ClientControl] 无玩家，无法执行 tweak");
            return;
        }

        // 获取当前位置和视角
        double currentX = client.player.getX();
        double currentY = client.player.getY();
        double currentZ = client.player.getZ();
        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        // 计算脚下方块坐标
        int blockX = (int) Math.floor(currentX);
        int blockY = (int) Math.floor(currentY - 0.0001); // 站在方块上时，脚的Y刚好是整数，减一点避免取到上一个方块
        int blockZ = (int) Math.floor(currentZ);

        // 计算新位置
        double newX = currentX;
        double newY = currentY;
        double newZ = currentZ;
        float newYaw = currentYaw;
        float newPitch = currentPitch;

        try {
            // X 坐标（相对方块）
            if (!xStr.equals("~")) {
                newX = blockX + Double.parseDouble(xStr);
            }

            // Y 坐标（相对方块）
            if (!yStr.equals("~")) {
                newY = blockY + Double.parseDouble(yStr);
            }

            // Z 坐标（相对方块）
            if (!zStr.equals("~")) {
                newZ = blockZ + Double.parseDouble(zStr);
            }

            // Yaw 视角（绝对角度）
            if (!yawStr.equals("~")) {
                newYaw = Float.parseFloat(yawStr);
            }

            // Pitch 视角（绝对角度）
            if (!pitchStr.equals("~")) {
                newPitch = Float.parseFloat(pitchStr);
            }
        } catch (NumberFormatException e) {
            client.player.sendMessage(Text.literal("§c参数格式错误，请输入数字或 ~"), false);
            return;
        }

        // 设置新位置和视角
        client.player.setPos(newX, newY, newZ);
        client.player.setYaw(newYaw);
        client.player.setPitch(newPitch);

        // 发送反馈消息
        client.player.sendMessage(Text.literal(String.format(
                "§a位置已微调: X=%.3f Y=%.3f Z=%.3f Yaw=%.1f Pitch=%.1f",
                newX, newY, newZ, newYaw, newPitch
        )), false);
    }
    // ==================== 视角控制 ====================

    /**
     * 设置玩家朝向角度
     * @param yaw 偏航角，null 表示保持不变
     * @param pitch 俯仰角，null 表示保持不变
     */
    private void setFacing(Float yaw, Float pitch) {
        if (client == null || client.player == null) {
            System.out.println("[ClientControl] 无法设置朝向：玩家不存在");
            return;
        }

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float newYaw = (yaw != null) ? yaw : currentYaw;
        float newPitch = (pitch != null) ? Math.clamp(pitch, -90.0f, 90.0f) : currentPitch;

        client.player.setYaw(newYaw);
        client.player.setPitch(newPitch);

        // 直接发送位置更新包（包含旋转信息）
        client.player.networkHandler.sendPacket(
                new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Full(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        newYaw,
                        newPitch,
                        client.player.isOnGround(),
                        client.player.horizontalCollision
                )
        );

        String yawDir = getYawName(newYaw);
        String pitchDir = getPitchName(newPitch);
        client.player.sendMessage(Text.literal(
                "§a视角已设置: §f" + yawDir + " (" + (int)newYaw + "°)" +
                        " §7| §a俯仰: §f" + pitchDir + " (" + (int)newPitch + "°)"
        ), false);
    }

    /**
     * 重置视角到当前朝向（仅显示提示）
     */
    private void showCurrentFacing() {
        if (client == null || client.player == null) return;

        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();

        String yawDir = getYawName(yaw);
        String pitchDir = getPitchName(pitch);

        client.player.sendMessage(Text.literal(
                "§6当前视角: §f" + yawDir + " (" + (int)yaw + "°)" +
                        " §7| §6俯仰: §f" + pitchDir + " (" + (int)pitch + "°)"
        ), false);
    }

    /**
     * 将角度转换为方向名称
     */
    private String getYawName(float yaw) {
        // 将角度归一化到 0-360
        float normalized = yaw % 360;
        if (normalized < 0) normalized += 360;

        if (normalized < 45 || normalized >= 315) return "南";
        if (normalized >= 45 && normalized < 135) return "西";
        if (normalized >= 135 && normalized < 225) return "北";
        if (normalized >= 225) return "东";
        return "未知";
    }
    /**
     * 将 Pitch 角度转换为描述
     */
    private String getPitchName(float pitch) {
        if (pitch < -75) return "正上方 (看天)";
        if (pitch < -45) return "仰视";
        if (pitch < -15) return "微仰";
        if (pitch <= 15) return "水平";
        if (pitch < 45) return "微俯";
        if (pitch < 75) return "俯视";
        return "正下方 (看地)";
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
                                        suppressManualUntilKeysReleased = true;
                                        executeWalk(dir, mode, mode.equals("press") ? 1 : -1);
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer())
                                            .executes(ctx -> {
                                                String dir = StringArgumentType.getString(ctx, "direction");
                                                String mode = StringArgumentType.getString(ctx, "mode");
                                                int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                                                suppressManualUntilKeysReleased = true;
                                                executeWalk(dir, mode, ticks);
                                                return 1;
                                            })
                                    )
                            )
                    )                    // tweak 子命令：在脚下方块上微调相对位置
                    .then(ClientCommandManager.literal("tweak")
                            .then(ClientCommandManager.argument("x", StringArgumentType.word())
                                    .then(ClientCommandManager.argument("y", StringArgumentType.word())
                                            .then(ClientCommandManager.argument("z", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        executeTweak(
                                                                StringArgumentType.getString(ctx, "x"),
                                                                StringArgumentType.getString(ctx, "y"),
                                                                StringArgumentType.getString(ctx, "z"),
                                                                "~", "~"
                                                        );
                                                        return 1;
                                                    })
                                                    .then(ClientCommandManager.argument("yaw", StringArgumentType.word())
                                                            .executes(ctx -> {
                                                                executeTweak(
                                                                        StringArgumentType.getString(ctx, "x"),
                                                                        StringArgumentType.getString(ctx, "y"),
                                                                        StringArgumentType.getString(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "yaw"),
                                                                        "~"
                                                                );
                                                                return 1;
                                                            })
                                                            .then(ClientCommandManager.argument("pitch", StringArgumentType.word())
                                                                    .executes(ctx -> {
                                                                        executeTweak(
                                                                                StringArgumentType.getString(ctx, "x"),
                                                                                StringArgumentType.getString(ctx, "y"),
                                                                                StringArgumentType.getString(ctx, "z"),
                                                                                StringArgumentType.getString(ctx, "yaw"),
                                                                                StringArgumentType.getString(ctx, "pitch")
                                                                        );
                                                                        return 1;
                                                                    })
                                                            )
                                                    )
                                            )
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

            //----------/ccgetid--------------
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
            // ---------- /ccfacing ----------
            dispatcher.register(ClientCommandManager.literal("ccfacing")
                    // 预设方向（仅 Yaw，Pitch 保持原样）
                    .then(ClientCommandManager.literal("north")
                            .executes(ctx -> {
                                setFacing(180.0f, null);
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("south")
                            .executes(ctx -> {
                                setFacing(0.0f, null);
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("east")
                            .executes(ctx -> {
                                setFacing(-90.0f, null);
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("west")
                            .executes(ctx -> {
                                setFacing(90.0f, null);
                                return 1;
                            }))
                    // 仅设置 Yaw（Pitch 保持不变）
                    .then(ClientCommandManager.literal("yaw")
                            .then(ClientCommandManager.argument("angle", FloatArgumentType.floatArg())
                                    .executes(ctx -> {
                                        float yaw = FloatArgumentType.getFloat(ctx, "angle");
                                        setFacing(yaw, null);
                                        return 1;
                                    }))
                    )
                    // 仅设置 Pitch（Yaw 保持不变）
                    .then(ClientCommandManager.literal("pitch")
                            .then(ClientCommandManager.argument("angle", FloatArgumentType.floatArg())
                                    .executes(ctx -> {
                                        float pitch = FloatArgumentType.getFloat(ctx, "angle");
                                        setFacing(null, pitch);
                                        return 1;
                                    }))
                    )
                    // 同时设置 Yaw 和 Pitch
                    .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("yaw", FloatArgumentType.floatArg())
                                    .then(ClientCommandManager.argument("pitch", FloatArgumentType.floatArg())
                                            .executes(ctx -> {
                                                float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                                                float pitch = FloatArgumentType.getFloat(ctx, "pitch");
                                                setFacing(yaw, pitch);
                                                return 1;
                                            }))
                            )
                    )
                    // 显示当前朝向
                    .then(ClientCommandManager.literal("get")
                            .executes(ctx -> {
                                showCurrentFacing();
                                return 1;
                            }))
            );

            // ========== /ccaction ==========
            dispatcher.register(ClientCommandManager.literal("ccaction")
                    .then(ClientCommandManager.literal("record")
                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name");
                                        startRecording(name);
                                        return 1;
                                    }))
                    )
                    .then(ClientCommandManager.literal("stop")
                            .executes(ctx -> {
                                if (isRecording) {
                                    stopRecording();
                                } else if (isPlaying) {
                                    stopPlayback();
                                } else {
                                    client.player.sendMessage(Text.literal("§c没有正在进行的录制或回放"), false);
                                }
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("play")
                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name");
                                        startPlayback(name);
                                        return 1;
                                    }))
                    )
                    .then(ClientCommandManager.literal("list")
                            .executes(ctx -> {
                                listRecordings();
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("config")
                            .executes(ctx -> {
                                openConfigScreen();
                                return 1;
                            }))
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

        speedUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.clientcontrol.speed_up",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UP,
                "category.clientcontrol"
        ));

        speedDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.clientcontrol.speed_down",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_DOWN,
                "category.clientcontrol"
        ));

        toggleBulldozerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.clientcontrol.toggle_bulldozer",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.clientcontrol"
        ));
    }

    // ==================== Tick ====================
    private void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // ===== 录制更新 =====
            if (isRecording) {
                recordTick();
            }

            // ===== 回放更新 =====
            if (isPlaying && client.player != null) {
                if (playbackQueue.isEmpty()) {
                    stopPlayback();
                    client.player.sendMessage(Text.literal("§a✅ 回放完成"), false);
                    return;
                }

                long elapsed = System.currentTimeMillis() - playbackStartTime;
                while (!playbackQueue.isEmpty()) {
                    String line = playbackQueue.peek();
                    String[] parts = line.split(":");
                    if (parts.length < 2) { playbackQueue.poll(); continue; }

                    long time = Long.parseLong(parts[parts.length - 1]);
                    if (time > elapsed) break;

                    playbackQueue.poll();
                    executePlaybackAction(parts);
                }
            }
            if (client.player == null) return;

            if (currentWalkTask != null) {
                currentWalkTask.tick();
                if(currentWalkTask != null)
                    currentWalkTask.applyMovement(client, speedMultiplier);
            }

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
                            client.player.sendMessage(Text.literal("§c如果你无缘无故接收到此消息，则可能是误判"), false);
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
            }if (speedUpKey.wasPressed()) {
                speedMultiplier = Math.min(1.0f, speedMultiplier + 0.05f);
                client.player.sendMessage(Text.literal("§a速度: " + (int)(speedMultiplier * 100) + "%"), false);
            }

            if (speedDownKey.wasPressed()) {
                speedMultiplier = Math.max(0.0f, speedMultiplier - 0.05f);
                client.player.sendMessage(Text.literal("§a速度: " + (int)(speedMultiplier * 100) + "%"), false);
            }

            if (toggleBulldozerKey.wasPressed()) {
                // 推土机开关逻辑（后续实现）
                client.player.sendMessage(Text.literal("§e推土机功能开发中..."), false);
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
            //client.player.sendMessage(Text.literal("§e命令已接收，将在 0.5 秒后执行..."), false);
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
        }, 1, TimeUnit.MILLISECONDS);

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

        void applyMovement(MinecraftClient client, float speedMultiplier) {
            if (client.player == null) return;
            if (this.dir == null) return;

            // 基础速度（原版行走速度约为 0.1）
            double baseSpeed = 0.1;
            double speed = baseSpeed * speedMultiplier;
            if (speedMultiplier <= 0.0f) {
                client.player.setVelocity(0, client.player.getVelocity().y, 0);
                return;
            }

            float yaw = client.player.getYaw();
            double rad = Math.toRadians(yaw);
            double vx, vz;

            // 根据方向计算速度向量
            switch (this.dir) {
                case TOWARD:
                    vx = -Math.sin(rad) * speed;
                    vz = Math.cos(rad) * speed;
                    break;
                case BACKWARD:
                    vx = Math.sin(rad) * speed;
                    vz = -Math.cos(rad) * speed;
                    break;
                case LEFT:
                    vx = Math.cos(rad) * speed;
                    vz = Math.sin(rad) * speed;
                    break;
                case RIGHT:
                    vx = -Math.cos(rad) * speed;
                    vz = -Math.sin(rad) * speed;
                    break;
                default:
                    vx = 0;
                    vz = 0;
                    break;
            }

            // 保留垂直速度（跳跃/下落不受影响）
            double vy = client.player.getVelocity().y;
            client.player.setVelocity(vx, vy, vz);
        }
    }
    // ==================== 录制系统 ====================

    private void startRecording(String name) {
        if (isRecording || isPlaying) {
            client.player.sendMessage(Text.literal("§c已在录制或回放中"), false);
            return;
        }

        try {
            recordingDir.mkdirs();
            File file = new File(recordingDir, name + ".txt");
            recordingWriter = new BufferedWriter(new FileWriter(file));

            recordingWriter.write("# ClientControl Recording: " + name);
            recordingWriter.newLine();
            recordingWriter.write("# Format: KEY:keyCode:pressed:time");
            recordingWriter.newLine();
            recordingWriter.write("#         MOUSE:button:pressed:time");
            recordingWriter.newLine();
            recordingWriter.write("#         LOOK:dyaw:dpitch:time");
            recordingWriter.newLine();
            recordingWriter.write("#         HOTBAR:slot:time");
            recordingWriter.newLine();
            recordingWriter.write("#         SCROLL:delta:time");
            recordingWriter.newLine();
            recordingWriter.write("#         CMD:command:time");
            recordingWriter.newLine();
            recordingWriter.write("#Record Version:" + RECORDING_VERSION);
            recordingWriter.newLine();
            recordingWriter.write("#=== START ===");
            recordingWriter.newLine();

            if (client.player != null) {
                recordingStartYaw = client.player.getYaw();
                recordingStartPitch = client.player.getPitch();
                recordingStartX = client.player.getX();
                recordingStartY = client.player.getY();
                recordingStartZ = client.player.getZ();
                recordingHotbarSlot = client.player.getInventory().selectedSlot;
            }

            // 重置去重状态
            lastKeyStates.clear();
            lastRecordedYaw = Float.NaN;
            lastRecordedPitch = Float.NaN;
            lastCalibrationTime = 0;
            flushCounter = 0;
            lastCalibrationTime = 0;

            isRecording = true;
            currentRecordingName = name;
            recordingStartTime = System.currentTimeMillis();

            recordInitialState();

            client.player.sendMessage(Text.literal("§a开始录制: " + name), false);
            System.out.println("[ClientControl] 开始录制: " + name);

        } catch (Exception e) {
            System.err.println("[ClientControl] 开始录制失败: " + e.getMessage());
        }
    }

    private void recordInitialState() {
        if (client.player == null) return;
        long time = System.currentTimeMillis() - recordingStartTime;

        try {
            // 初始化所有录制按键的状态为未按下
            lastKeyStates.clear();
            for (int keyCode : RECORDED_KEYS) {
                lastKeyStates.put(keyCode, false);
            }

            // ====== 记录初始物品栏槽位 ======
            try {
                java.lang.reflect.Field field = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
                field.setAccessible(true);
                int initialSlot = field.getInt(client.player.getInventory());
                recordingWriter.write("SLOT:" + initialSlot + ":" + time);
                recordingWriter.newLine();
            } catch (Exception e) {
                System.err.println("[ClientControl] 记录初始槽位失败: " + e.getMessage());
            }

            // 记录初始视角
            recordingWriter.write("LOOK:0.0:0.0:" + time);
            recordingWriter.newLine();
            recordingWriter.flush();
        } catch (Exception e) {
            System.err.println("[ClientControl] 记录初始状态失败: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            client.player.sendMessage(Text.literal("§c没有正在进行的录制"), false);
            return;
        }

        try {
            if (recordingWriter != null) {
                recordingWriter.write("#=== END ===");
                recordingWriter.newLine();
                recordingWriter.close();
                recordingWriter = null;
            }

            isRecording = false;
            client.player.sendMessage(Text.literal("§a录制已保存: " + currentRecordingName), false);
            System.out.println("[ClientControl] 录制已保存: " + currentRecordingName);

        } catch (Exception e) {
            System.err.println("[ClientControl] 保存录制失败: " + e.getMessage());
        }
    }

    // ==================== 方块事件回调（供 Mixin 调用） ====================

    /**
     * 玩家开始挖掘方块时调用
     */
    public void onBlockBreakStart(net.minecraft.util.math.BlockPos pos) {
        if (!isRecording || recordingWriter == null || client.player == null) return;

        try {
            long time = System.currentTimeMillis() - recordingStartTime;
            // 玩家相对位置和视角（相对于录制起始点）
            double px = client.player.getX() - recordingStartX;
            double py = client.player.getY() - recordingStartY;
            double pz = client.player.getZ() - recordingStartZ;
            float yaw = client.player.getYaw() - recordingStartYaw;
            float pitch = client.player.getPitch() - recordingStartPitch;

            // 记录挖掘开始事件 + 位置视角校准
            // 格式：BREAK_START:bx:by:bz:px:py:pz:yaw:pitch:time
            // 注意：bx,by,bz 是方块的绝对坐标；px,py,pz,yaw,pitch 是玩家的相对值
            recordingWriter.write(String.format("BREAK_START:%d:%d:%d:%.3f:%.3f:%.3f:%.2f:%.2f:%d",
                    pos.getX(), pos.getY(), pos.getZ(),
                    px, py, pz, yaw, pitch, time));
            recordingWriter.newLine();
            // 不单独 flush，统一在 recordTick 末尾写，减少磁盘 IO
        } catch (Exception e) {
            System.err.println("[ClientControl] 记录挖掘开始失败: " + e.getMessage());
        }
    }

    /**
     * 玩家完成挖掘方块时调用
     */
    public void onBlockBreakComplete(net.minecraft.util.math.BlockPos pos) {
        if (!isRecording || recordingWriter == null || client.player == null) return;

        try {
            long time = System.currentTimeMillis() - recordingStartTime;
            // 记录挖掘完成事件
            recordingWriter.write(String.format("BREAK:%d:%d:%d:%d",
                    pos.getX(), pos.getY(), pos.getZ(), time));
            recordingWriter.newLine();
            recordingWriter.flush();
        } catch (Exception e) {
            System.err.println("[ClientControl] 记录挖掘完成失败: " + e.getMessage());
        }
    }

    /**
     * 玩家放置方块时调用
     */
    public void onBlockPlace(net.minecraft.util.math.BlockPos pos) {
        if (!isRecording || recordingWriter == null || client.player == null) return;

        try {
            long time = System.currentTimeMillis() - recordingStartTime;
            // 玩家相对位置和视角（相对于录制起始点）
            double px = client.player.getX() - recordingStartX;
            double py = client.player.getY() - recordingStartY;
            double pz = client.player.getZ() - recordingStartZ;
            float yaw = client.player.getYaw() - recordingStartYaw;
            float pitch = client.player.getPitch() - recordingStartPitch;

            // 记录放置事件 + 位置视角校准
            // 格式：PLACE:bx:by:bz:px:py:pz:yaw:pitch:time
            // 注意：bx,by,bz 是方块的绝对坐标；px,py,pz,yaw,pitch 是玩家的相对值
            recordingWriter.write(String.format("PLACE:%d:%d:%d:%.3f:%.3f:%.3f:%.2f:%.2f:%d",
                    pos.getX(), pos.getY(), pos.getZ(),
                    px, py, pz, yaw, pitch, time));
            recordingWriter.newLine();
            // 不单独 flush，统一在 recordTick 末尾写，减少磁盘 IO
        } catch (Exception e) {
            System.err.println("[ClientControl] 记录放置失败: " + e.getMessage());
        }
    }

    private void recordTick() {
        if (!isRecording || client.player == null) return;

        long time = System.currentTimeMillis() - recordingStartTime;
        long windowHandle = client.getWindow().getHandle();

        try {
            // ====== 要录制的键盘按键列表 ======
            int[] keyboardKeys = {
                GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                GLFW.GLFW_KEY_SPACE,
                GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
                GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
                GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3,
                GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6,
                GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9,
                GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_F
            };

            // 检测键盘按键状态变化
            for (int keyCode : keyboardKeys) {
                boolean pressed = GLFW.glfwGetKey(windowHandle, keyCode) == GLFW.GLFW_PRESS;
                Boolean lastPressed = lastKeyStates.get(keyCode);

                if (lastPressed == null || pressed != lastPressed) {
                    lastKeyStates.put(keyCode, pressed);
                    recordingWriter.write("KEY:" + keyCode + ":" + (pressed ? 1 : 0) + ":" + time);
                    recordingWriter.newLine();
                }
            }

            // ====== 要录制的鼠标按键列表 ======
            int[] mouseButtons = {
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                GLFW.GLFW_MOUSE_BUTTON_RIGHT
            };

            // 检测鼠标按键状态变化
            for (int button : mouseButtons) {
                boolean pressed = GLFW.glfwGetMouseButton(windowHandle, button) == GLFW.GLFW_PRESS;
                // 用负数表示鼠标按键，避免和键盘按键码冲突
                int mapKey = -1 - button;
                Boolean lastPressed = lastKeyStates.get(mapKey);

                if (lastPressed == null || pressed != lastPressed) {
                    lastKeyStates.put(mapKey, pressed);
                    recordingWriter.write("MOUSE:" + button + ":" + (pressed ? 1 : 0) + ":" + time);
                    recordingWriter.newLine();
                }
            }

            // ====== 视角变化检测 ======
            float relYaw = client.player.getYaw() - recordingStartYaw;
            float relPitch = client.player.getPitch() - recordingStartPitch;
            boolean lookChanged = Float.isNaN(lastRecordedYaw) ||
                    Math.abs(relYaw - lastRecordedYaw) > 0.5 ||
                    Math.abs(relPitch - lastRecordedPitch) > 0.5;

            if (lookChanged) {
                recordingWriter.write(String.format("LOOK:%.1f:%.1f:%d", relYaw, relPitch, time));
                recordingWriter.newLine();
                lastRecordedYaw = relYaw;
                lastRecordedPitch = relPitch;
            }

            // ====== 快捷栏变化检测 ======
            int currentSlot = client.player.getInventory().selectedSlot;
            if (currentSlot != recordingHotbarSlot) {
                recordingHotbarSlot = currentSlot;
                recordingWriter.write("HOTBAR:" + currentSlot + ":" + time);
                recordingWriter.newLine();
            }

            // ====== 滚轮录制 ======
            double scrollDelta = getAndClearScrollDelta();
            if (scrollDelta != 0) {
                // 记录滚轮（格式：SCROLL:delta:time）
                recordingWriter.write("SCROLL:" + scrollDelta + ":" + time);
                recordingWriter.newLine();
            }

            // ====== 定期位置校准 ======
            if (config.calibrationRate > 0 && time - lastCalibrationTime >= config.calibrationRate) {
                lastCalibrationTime = time;
                // 记录相对位置和相对视角（相对于录制起始点）
                double dx = client.player.getX() - recordingStartX;
                double dy = client.player.getY() - recordingStartY;
                double dz = client.player.getZ() - recordingStartZ;
                float dyaw = client.player.getYaw() - recordingStartYaw;
                float dpitch = client.player.getPitch() - recordingStartPitch;
                recordingWriter.write(String.format("CALIB:%.3f:%.3f:%.3f:%.2f:%.2f:%d",
                        dx, dy, dz, dyaw, dpitch, time));
                recordingWriter.newLine();
            }

            // 每 N tick 刷一次盘，减少磁盘 IO
            flushCounter++;
            if (flushCounter >= FLUSH_INTERVAL) {
                recordingWriter.flush();
                flushCounter = 0;
            }
        } catch (Exception e) {
            System.err.println("[ClientControl] 录制出错: " + e.getMessage());
        }
    }

    private void listRecordings() {
        recordingDir.mkdirs();
        File[] files = recordingDir.listFiles((dir, name) -> name.endsWith(".txt"));

        if (files == null || files.length == 0) {
            client.player.sendMessage(Text.literal("§e没有找到录制文件"), false);
            return;
        }

        client.player.sendMessage(Text.literal("§6=== 录制文件列表 ==="), false);
        for (File file : files) {
            String name = file.getName().replace(".txt", "");
            client.player.sendMessage(Text.literal("§7- " + name), false);
        }
    }

    // ==================== 回放系统 ====================

    private void startPlayback(String name) {
        if (isPlaying || isRecording) {
            client.player.sendMessage(Text.literal("§c已在回放或录制中"), false);
            return;
        }

        File file = new File(recordingDir, name + ".txt");
        if (!file.exists()) {
            client.player.sendMessage(Text.literal("§c未找到录制: " + name), false);
            return;
        }

        try {
            playbackQueue.clear();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            boolean inRecording = false;
            boolean rightversion = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#Record Version:" + RECORDING_VERSION)) rightversion = true;
                if (line.startsWith("#=== START ===") && rightversion) { inRecording = true; continue; }
                if (line.startsWith("#=== END ===")) break;
                if (line.startsWith("#")) continue;
                if (inRecording) {
                    // 给鼠标左键释放加 1 tick（50ms），保证挖掘/攻击能被游戏检测到
                    if (line.startsWith("MOUSE:0:0:")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 4) {
                            try {
                                int time = Integer.parseInt(parts[3]) + 50;
                                line = "MOUSE:0:0:" + time;
                            } catch (NumberFormatException e) {
                                System.out.println("解析失败：无法在左键中插入1tick");
                                // 解析失败就用原来的
                            }
                        }
                    }
                    playbackQueue.add(line);
                }
            }
            reader.close();

            if (playbackQueue.isEmpty()) {
                client.player.sendMessage(Text.literal("§c录制文件为空或版本不兼容"), false);
                return;
            }

            isPlaying = true;
            currentRecordingName = name;
            playbackStartTime = System.currentTimeMillis();

            // 记录回放起始视角和位置
            if (client.player != null) {
                playbackStartYaw = client.player.getYaw();
                playbackStartPitch = client.player.getPitch();
                playbackStartX = client.player.getX();
                playbackStartY = client.player.getY();
                playbackStartZ = client.player.getZ();
                playbackHotbarSlot = client.player.getInventory().selectedSlot;
            }

            client.player.sendMessage(Text.literal("§a▶️ 开始回放: " + name +
                    " (" + playbackQueue.size() + " 帧)"), false);
            System.out.println("[ClientControl] 开始回放: " + name);

        } catch (Exception e) {
            System.err.println("[ClientControl] 加载录制失败: " + e.getMessage());
            client.player.sendMessage(Text.literal("§c加载失败: " + e.getMessage()), false);
        }
    }

    private void stopPlayback() {
        if (!isPlaying) {
            client.player.sendMessage(Text.literal("§c没有正在进行的回放"), false);
            return;
        }

        isPlaying = false;
        playbackQueue.clear();

        // 重置所有按键状态
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            client.options.useKey.setPressed(false);
            client.options.inventoryKey.setPressed(false);
            client.options.dropKey.setPressed(false);
            client.options.swapHandsKey.setPressed(false);
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c回放已停止"), false);
        }
        System.out.println("[ClientControl] 回放已停止");
    }
    // ==================== 寻路系统 ====================

    private void executePlaybackAction(String[] parts) {
        String type = parts[0];

        switch (type) {
            case "KEY":
                // KEY:keyCode:pressed:time
                if (parts.length >= 4) {
                    int keyCode = Integer.parseInt(parts[1]);
                    boolean pressed = Integer.parseInt(parts[2]) == 1;
                    simulateKeyByGLFWCode(keyCode, pressed);
                }
                break;

            case "MOUSE":
                // MOUSE:button:pressed:time
                if (parts.length >= 4) {
                    int button = Integer.parseInt(parts[1]);
                    boolean pressed = Integer.parseInt(parts[2]) == 1;
                    simulateKeyByGLFWCode(button, pressed);
                }
                break;

            case "ALLKEYS":
                // 兼容旧格式 ALLKEYS:key0:key1:...:key511:time
                if (parts.length >= 3) {
                    for (int i = 1; i < parts.length - 1; i++) {
                        int keyCode = i - 1;
                        if (keyCode >= 512) break;
                        boolean pressed = Integer.parseInt(parts[i]) == 1;
                        simulateKeyByGLFWCode(keyCode, pressed);
                    }
                }
                break;

            case "HOTBAR":
                // HOTBAR:slot:time
                if (parts.length >= 3) {
                    int slot = Integer.parseInt(parts[1]);
                    if (client.player != null) {
                        client.player.getInventory().setSelectedSlot(slot);
                    }
                }
                break;

            case "SLOT":
                // SLOT:slotIndex:time - 设置物品栏槽位
                if (parts.length >= 3) {
                    int slot = Integer.parseInt(parts[1]);
                    if (client.player != null) {
                        try {
                            java.lang.reflect.Field field = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
                            field.setAccessible(true);
                            field.setInt(client.player.getInventory(), slot);
                        } catch (Exception e) {
                            System.err.println("[ClientControl] 设置槽位失败: " + e.getMessage());
                        }
                    }
                }
                break;

            case "SCROLL":
                // SCROLL:delta:time - 滚轮滚动
                if (parts.length >= 3) {
                    double deltaDouble = Double.parseDouble(parts[1]);
                    int delta = (int) Math.round(deltaDouble);

                    if (delta != 0 && client.player != null) {
                        try {
                            java.lang.reflect.Field field = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
                            field.setAccessible(true);
                            int current = field.getInt(client.player.getInventory());

                            // 注意：Minecraft 滚轮向上 = 序号减小（向左），向下 = 序号增大（向右）
                            // delta > 0 是向上滚，所以用 current - delta
                            int newSlot = (current - delta + 10) % 9;
                            if (newSlot < 0) newSlot += 9;

                            field.setInt(client.player.getInventory(), newSlot);
                        } catch (Exception e) {
                            System.err.println("[ClientControl] 滚轮切换物品栏失败: " + e.getMessage());
                        }
                    }
                }
                break;

            case "LOOK":
                // LOOK:relYaw:relPitch:time
                if (parts.length >= 4) {
                    float relYaw = Float.parseFloat(parts[1]);
                    float relPitch = Float.parseFloat(parts[2]);
                    if (client.player != null) {
                        client.player.setYaw(playbackStartYaw + relYaw);
                        client.player.setPitch(playbackStartPitch + relPitch);
                    }
                }
                break;

            case "CALIB":
                // CALIB:dx:dy:dz:dyaw:dpitch:time - 位置校准点（相对值）
                // 用于修正按键回放的累积误差，确保关键位置精准
                if (parts.length >= 6) {
                    try {
                        double dx = Double.parseDouble(parts[1]);
                        double dy = Double.parseDouble(parts[2]);
                        double dz = Double.parseDouble(parts[3]);
                        float dyaw = Float.parseFloat(parts[4]);
                        float dpitch = Float.parseFloat(parts[5]);

                        if (client.player != null) {
                            // 回放起始位置 + 相对偏移 = 实际位置
                            client.player.setPos(playbackStartX + dx, playbackStartY + dy, playbackStartZ + dz);
                            client.player.setYaw(playbackStartYaw + dyaw);
                            client.player.setPitch(playbackStartPitch + dpitch);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("[ClientControl] 校准点解析失败: " + e.getMessage());
                    }
                }
                break;

            case "BREAK_START":
                // BREAK_START:bx:by:bz:px:py:pz:yaw:pitch:time
                // 开始挖掘方块 - 精准校准位置和视角，确保对准方块
                // 注意：px,py,pz,yaw,pitch 是玩家的相对值（相对于录制起始点）
                if (parts.length >= 9) {
                    try {
                        // 检查配置：只有"全部校准"模式才校准挖掘
                        int mode = com.qiujunawa.clientcontrol.config.ClientControlConfig.blockEventCalibration.getIntegerValue();
                        if (mode >= 2 && client.player != null) {
                            // 方块位置（暂时用于参考，主要用玩家位置校准）
                            // int bx = Integer.parseInt(parts[1]);
                            // int by = Integer.parseInt(parts[2]);
                            // int bz = Integer.parseInt(parts[3]);

                            // 玩家相对位置和视角
                            double px = Double.parseDouble(parts[4]);
                            double py = Double.parseDouble(parts[5]);
                            double pz = Double.parseDouble(parts[6]);
                            float yaw = Float.parseFloat(parts[7]);
                            float pitch = Float.parseFloat(parts[8]);

                            // 回放起始位置 + 相对偏移 = 实际位置
                            client.player.setPos(playbackStartX + px, playbackStartY + py, playbackStartZ + pz);
                            client.player.setYaw(playbackStartYaw + yaw);
                            client.player.setPitch(playbackStartPitch + pitch);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("[ClientControl] 挖掘开始事件解析失败: " + e.getMessage());
                    }
                }
                break;

            case "BREAK":
                // BREAK:bx:by:bz:time
                // 挖掘完成 - 可用于验证或额外校准
                if (parts.length >= 4) {
                    // 目前只记录，不做特殊处理
                    // 以后可以加：验证方块是否真的被挖掉，没挖掉就补一下
                }
                break;

            case "PLACE":
                // PLACE:bx:by:bz:px:py:pz:yaw:pitch:time
                // 放置方块 - 精准校准位置和视角，确保对准放置点
                // 注意：px,py,pz,yaw,pitch 是玩家的相对值（相对于录制起始点）
                if (parts.length >= 9) {
                    try {
                        // 方块位置（暂时用于参考）
                        // int bx = Integer.parseInt(parts[1]);
                        // int by = Integer.parseInt(parts[2]);
                        // int bz = Integer.parseInt(parts[3]);

                        // 玩家相对位置和视角
                        double px = Double.parseDouble(parts[4]);
                        double py = Double.parseDouble(parts[5]);
                        double pz = Double.parseDouble(parts[6]);
                        float yaw = Float.parseFloat(parts[7]);
                        float pitch = Float.parseFloat(parts[8]);

                        // 检查配置：仅放置 和 全部校准 模式都校准放置
                        int mode = com.qiujunawa.clientcontrol.config.ClientControlConfig.blockEventCalibration.getIntegerValue();
                        if (mode >= 1 && client.player != null) {
                            // 回放起始位置 + 相对偏移 = 实际位置
                            client.player.setPos(playbackStartX + px, playbackStartY + py, playbackStartZ + pz);
                            client.player.setYaw(playbackStartYaw + yaw);
                            client.player.setPitch(playbackStartPitch + pitch);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("[ClientControl] 放置事件解析失败: " + e.getMessage());
                    }
                }
                break;

            case "CMD":
                // CMD:command:time
                if (parts.length >= 3) {
                    String command = parts[1];
                    if (client.player != null && command != null && !command.isEmpty()) {
                        client.player.networkHandler.sendChatMessage(command);
                    }
                }
                break;
        }
    }
}
