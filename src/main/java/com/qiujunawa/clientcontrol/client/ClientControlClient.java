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

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ClientControlClient implements ClientModInitializer {

	private static ClientControlClient INSTANCE;
	private MinecraftClient client;

	// 自动行走任务
	private AutoWalkTask currentWalkTask = null;

	// 速度倍率
	private float speedMultiplier = 1.0f;

	// 快捷键
	private static KeyBinding toggleWalkKey;

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
			dispatcher.register(literal("ccwalk")
					.then(argument("direction", StringArgumentType.word())
							.suggests((ctx, builder) -> {
								builder.suggest("toward");
								builder.suggest("backward");
								builder.suggest("left");
								builder.suggest("right");
								return builder.buildFuture();
							})
							.then(argument("mode", StringArgumentType.word())
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
									.then(argument("ticks", IntegerArgumentType.integer())
											executes(ctx -> {
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
			dispatcher.register(literal("ccsetspeed")
					.then(argument("multiplier", FloatArgumentType.floatArg(0, 1))
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

			dispatcher.register(literal("ccgetid")
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

			if (toggleWalkKey.wasPressed()) {
				if (currentWalkTask != null && currentWalkTask.isActive()) {
					currentWalkTask.stop();
					client.player.sendMessage(Text.literal("§c自动行走已关闭"), false);
				} else {
					executeWalk("toward", "hold", -1);
				}
			}
		});
	}

	// ==================== 执行逻辑 ====================
	// 修改 executeWalk 中 press 分支以及增加 player 空检查
	private void executeWalk(String direction, String mode, int ticks) {
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
			// 清理外部引用以便垃圾回收，并让逻辑更清晰
			currentWalkTask = null;
		}

		boolean isActive() {
			return remainingTicks != 0;
		}
	}
}
