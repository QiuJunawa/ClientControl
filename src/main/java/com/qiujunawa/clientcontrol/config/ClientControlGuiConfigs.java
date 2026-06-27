package com.qiujunawa.clientcontrol.config;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import com.qiujunawa.clientcontrol.Reference;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class ClientControlGuiConfigs extends GuiConfigsBase {

    private static ConfigGuiTab currentTab = ConfigGuiTab.GENERAL;

    /**
     * 添加一个循环切换按钮
     * @param x 按钮X坐标
     * @param y 按钮Y坐标
     * @param config 配置项（必须是整数类型）
     * @param displayName 显示名称（会显示在按钮上）
     * @param modeTexts 模式对应的显示文字（按顺序，索引对应值）
     */
    private void addCycleButton(int x, int y, ConfigInteger config, String displayName, String... modeTexts) {
        int currentValue = config.getIntegerValue();
        String currentText = (currentValue >= 0 && currentValue < modeTexts.length)
                ? modeTexts[currentValue]
                : "未知(" + currentValue + ")";

        ButtonGeneric button = new ButtonGeneric(x, y, 200, 20,
                "§b" + displayName + ": " + currentText);

        this.addButton(button, (btn, mbtn) -> {
            int current = config.getIntegerValue();
            int next = (current + 1) % modeTexts.length;
            config.setIntegerValue(next);
            this.initGui(); // 刷新界面更新文字
        });
    }

    public ClientControlGuiConfigs() {
        super(10, 50, Reference.MOD_ID, null, "clientcontrol.gui.title");
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs;

        switch (currentTab) {
            case GENERAL:
                configs = getGeneralConfigs();
                break;
            case RECORDING:
                configs = getRecordingConfigs();
                break;
            case MOVEMENT:
                configs = getMovementConfigs();
                break;
            case BULLDOZER:
                configs = getBulldozerConfigs();
                break;
            case ADVANCED:
                configs = getAdvancedConfigs();
                break;
            default:
                configs = new ArrayList<>();
        }

        return ConfigOptionWrapper.createFor(configs);
    }

    private List<IConfigBase> getGeneralConfigs() {
        List<IConfigBase> list = new ArrayList<>();
        list.add(ClientControlConfig.enableMod);
        list.add(ClientControlConfig.enableAutoWalk);
        list.add(ClientControlConfig.enableRecording);
        list.add(ClientControlConfig.enableBulldozer);
        return list;
    }

    private List<IConfigBase> getRecordingConfigs() {
        List<IConfigBase> list = new ArrayList<>();
        list.add(ClientControlConfig.calibrationRate);
        //list.add(ClientControlConfig.blockEventCalibration);
        list.add(ClientControlConfig.blockEventCalibrationDummy);
        list.add(ClientControlConfig.recordingFlushInterval);
        list.add(ClientControlConfig.maxRecordingTime);
        return list;
    }

    private List<IConfigBase> getMovementConfigs() {
        List<IConfigBase> list = new ArrayList<>();
        list.add(ClientControlConfig.moveSpeed);
        list.add(ClientControlConfig.defaultWalkTicks);
        list.add(ClientControlConfig.sneakSpeedMultiplier);
        return list;
    }

    private List<IConfigBase> getBulldozerConfigs() {
        List<IConfigBase> list = new ArrayList<>();
        list.add(ClientControlConfig.bulldozerWidth);
        list.add(ClientControlConfig.bulldozerHeight);
        list.add(ClientControlConfig.bulldozerMargin);
        list.add(ClientControlConfig.bulldozerDepth);
        list.add(ClientControlConfig.bulldozerFillMode);
        return list;
    }

    private List<IConfigBase> getAdvancedConfigs() {
        List<IConfigBase> list = new ArrayList<>();
        list.add(ClientControlConfig.borderProtection);
        list.add(ClientControlConfig.borderMargin);
        list.add(ClientControlConfig.stopScreenRender);
        list.add(ClientControlConfig.debugMode);
        return list;
    }

    @Override
    protected int getConfigWidth() {
        return 200;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearButtons();

        int totalWidth = ConfigGuiTab.values().length * 60;
        int startX = 10; // 直接靠左，与配置列表左侧对齐
        int y = 26;
        int count = 0;
        int[] length = new int[]{30, 30, 30, 40, 30};


        for (ConfigGuiTab tab : ConfigGuiTab.values()) {
            int width;
            if (count >= length.length) {
                width = 30;
                System.err.println("[ClientControl]数组越界！宽度已重置为30");
            }else {
                width = length[count];
            }
            boolean isSelected = (currentTab == tab);
            // 选中的标签页用灰色文字，不选中的用白色
            // 从语言文件读取翻译后的文字
            String translatedName = Text.translatable(tab.translationKey).getString();
            String label = isSelected ? "§7" + translatedName : translatedName;
            ButtonGeneric button = new ButtonGeneric(startX, y, width, 20, label);

            if (isSelected) {
                // 当前标签页 → 禁用按钮，不可点击
                button.setEnabled(false);
                // 添加按钮但不加监听器（或加空监听器）
                this.addButton(button, (btn, mbtn) -> {});
            } else {
                // 其他标签页 → 可点击切换
                button.setEnabled(true);
                this.addButton(button, (btn, mbtn) -> {
                    if (currentTab != tab) {
                        currentTab = tab;
                        this.reCreateListWidget();
                        this.initGui();
                    }
                });
            }
            startX = startX + width + 3;
            count++;
        }

        ButtonGeneric resetButton = new ButtonGeneric(this.width - 170, 26, 70, 20, "§c重置");
        this.addButton(resetButton, (btn, mbtn) -> {
            resetCurrentTabConfigs();
            this.reCreateListWidget();
            this.initGui();
        });

        ButtonGeneric saveButton = new ButtonGeneric(this.width - 95, 26, 80, 20, "§a保存并退出");
        this.addButton(saveButton, (btn, mbtn) -> {
            ClientControlConfig.INSTANCE.save();
            if (this.client != null && this.client.player != null) {
                this.client.player.sendMessage(Text.literal("§a配置已保存"), false);
            }
            this.close();
        });

        if (currentTab == ConfigGuiTab.RECORDING) {
            addCycleButton(10, this.height - 35,
                    ClientControlConfig.blockEventCalibration,
                    "方块事件校准",
                    "关闭", "仅放置校准", "全部校准");
        }
    }

    private void resetCurrentTabConfigs() {
        List<IConfigBase> configs;
        switch (currentTab) {
            case GENERAL:
                configs = getGeneralConfigs();
                break;
            case RECORDING:
                configs = getRecordingConfigs();
                break;
            case MOVEMENT:
                configs = getMovementConfigs();
                break;
            case BULLDOZER:
                configs = getBulldozerConfigs();
                break;
            case ADVANCED:
                configs = getAdvancedConfigs();
                break;
            default:
                return;
        }

        for (IConfigBase config : configs) {
            if (config instanceof IConfigValue) {
                ((IConfigValue) config).resetToDefault();
            }
        }
    }

    public enum ConfigGuiTab {
        GENERAL("config.clientcontrol.gui.tab.general"),
        RECORDING("config.clientcontrol.gui.tab.recording"),
        MOVEMENT("config.clientcontrol.gui.tab.movement"),
        BULLDOZER("config.clientcontrol.gui.tab.bulldozer"),
        ADVANCED("config.clientcontrol.gui.tab.advanced");

        public final String translationKey;

        ConfigGuiTab(String translationKey) {
            this.translationKey = translationKey;
        }
    }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.world != null) {
            // 游戏内：绘制半透明黑色背景（原有风格）
            context.fill(0, 0, this.width, this.height, 0x00000000);
        } else {
            // 主菜单：不绘制背景，让主菜单背景透出来
            // 什么都不做
            context.fill(0, 0, this.width, this.height, 0x00000000);
        }
        //super.render(context, mouseX, mouseY, delta);
    }
    public static GuiConfigsBase createConfigScreen() {
        return new ClientControlGuiConfigs();
    }
}