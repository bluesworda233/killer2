import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

/**
 * 功能逻辑：主循环每帧调用 tick(mf, n)，执行所有已开启的 apply* 与 clearWeaponCooldown。
 * 开关与 last* 由 GameAgent 暴露给 UI；本类持有 apply* 实现与 last*、aimbot 锁定状态。
 */
public final class GameAgentFeatures {

    private static final float RANGED_COOLDOWN_SET = 0f;
    private static final float MELEE_ANIM_SPEED_MULTIPLIER = 3f;

    private static int zeroCooldownTickCount = 0;
    private static volatile String lastZeroCooldownStatus = "";
    private static volatile String lastMeleeDebugStatus = "";
    private static volatile String lastVisionDebugStatus = "";
    private static volatile String lastAimbotDebugStatus = "";

    private static volatile Object aimbotLockedTargetRef = null;
    private static volatile Object aimbotLockedTargetPos = null;
    private static volatile String aimbotLockedTargetName = "";
    private static volatile String aimbotLockedTargetPlayerName = "";
    private static volatile boolean aimbotAimHeld = false;

    private GameAgentFeatures() { }

    /** 主循环每帧调用：执行所有已开启的 apply* 与 clearWeaponCooldown。 */
    public static void tick(Object mf, long n) {
        if (mf == null) return;
        try {
            if (GameAgent.getZeroCooldownEnabled()) clearWeaponCooldown(mf);
            if (GameAgent.getAimbotEnabled()) applyAimbot(mf);
            if (GameAgent.getPerfectBlockEnabled()) applyPerfectBlock(mf);
            if (GameAgent.getMeleeAnimSpeedEnabled() && (n % 2 == 0)) applyMeleeAnimSpeed(mf);
            if (n % 15 == 0) {
                if (GameAgent.getNoRecoilEnabled()) applyNoRecoil(mf);
                if (GameAgent.getVisionBypassEnabled()) applyVisionBypass(mf);
                if (GameAgent.getInteractDistanceEnabled()) applyInteractDistanceToggle(mf);
                if (GameAgent.getPerfectPickpocketEnabled()) applyPerfectPickpocketMinigame(mf);
                if (GameAgent.getFrontPickpocketEnabled()) applyFrontPickpocket(mf);
                if (GameAgent.getAlwaysSprintEnabled()) applyAlwaysSprint(mf);
            }
        } catch (Throwable ignored) { }
    }

    /** 主窗口关闭时由 GameAgent.resetOnDebugWindowClosed 调用，清空 last* 与 aimbot 锁定状态。 */
    public static void clearFeatureState() {
        lastZeroCooldownStatus = "";
        lastMeleeDebugStatus = "";
        lastVisionDebugStatus = "";
        lastAimbotDebugStatus = "";
        aimbotLockedTargetRef = null;
        aimbotLockedTargetPos = null;
        aimbotLockedTargetName = "";
        aimbotLockedTargetPlayerName = "";
        aimbotAimHeld = false;
    }

    public static String getLastZeroCooldownStatus() { return lastZeroCooldownStatus; }
    /** Overlay 显示时由 GameAgent 调用，设置简单提示文案（如「快速开火已开启」）。 */
    public static void setLastZeroCooldownStatus(String s) { lastZeroCooldownStatus = s != null ? s : ""; }
    public static String getLastMeleeDebugStatus() { return lastMeleeDebugStatus; }
    public static String getLastVisionDebugStatus() { return lastVisionDebugStatus; }
    public static String getLastAimbotDebugStatus() { return lastAimbotDebugStatus; }
    public static Object getAimbotLockedTargetRef() { return aimbotLockedTargetRef; }
    public static Object getAimbotLockedTargetPos() { return aimbotLockedTargetPos; }
    public static String getAimbotLockedTargetName() { return aimbotLockedTargetName; }
    public static String getAimbotLockedTargetPlayerName() { return aimbotLockedTargetPlayerName; }
    public static boolean getAimbotAimHeld() { return aimbotAimHeld; }

    static void clearWeaponCooldown(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) {
                zeroCooldownTickCount++;
                if (zeroCooldownTickCount % 40 == 1)
                    lastZeroCooldownStatus = "[快速开火] ki=null（未进 KI 对局？）";
                return;
            }
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object player = getActivePlayer.invoke(ki);
            if (player == null) {
                zeroCooldownTickCount++;
                if (zeroCooldownTickCount % 40 == 1)
                    lastZeroCooldownStatus = "[快速开火] player=null（未进对局或未生成角色）";
                return;
            }
            Method getInventoryController = player.getClass().getMethod("getInventoryController");
            Object inv = getInventoryController.invoke(player);
            if (inv == null) {
                zeroCooldownTickCount++;
                if (zeroCooldownTickCount % 40 == 1)
                    lastZeroCooldownStatus = "[快速开火] inv=null";
                return;
            }
            Method getKIItems = inv.getClass().getMethod("getKIItems");
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) getKIItems.invoke(inv);
            if (items == null || items.isEmpty()) {
                zeroCooldownTickCount++;
                if (zeroCooldownTickCount % 40 == 1)
                    lastZeroCooldownStatus = "[快速开火] 背包无物品（需在对局内且背包有武器）";
                return;
            }
            Class<?> itemTypeClass = Class.forName("KI.Game.General.Resource.KIItemData$KIItemType");
            Object meleeType = Enum.valueOf((Class<Enum>) itemTypeClass, "MELEE");
            Method getWeapon = Class.forName("KI.Game.General.KIItem").getMethod("getWeapon");
            Method hasItemType = Class.forName("KI.Game.Weapon.KIWeapon").getMethod("hasItemType", itemTypeClass);
            int rangedCount = 0;
            for (Object item : items) {
                if (item == null) continue;
                Object weapon = getWeapon.invoke(item);
                if (weapon == null) continue;
                boolean isMelee = Boolean.TRUE.equals(hasItemType.invoke(weapon, meleeType));
                if (isMelee) continue;
                setCooldownAndCooldownTime(weapon, RANGED_COOLDOWN_SET);
                rangedCount++;
            }
            zeroCooldownTickCount++;
            lastZeroCooldownStatus = "[快速开火] 远程武器数=" + rangedCount + " 已设 cooldown/cooldownTime=0";
        } catch (Throwable t) {
            zeroCooldownTickCount++;
            lastZeroCooldownStatus = "[快速开火] 异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }

    static void setCooldownAndCooldownTime(Object weapon, float value) {
        try {
            Class<?> wc = weapon.getClass();
            Field cooldownField = wc.getDeclaredField("cooldown");
            Field cooldownTimeField = wc.getDeclaredField("cooldownTime");
            cooldownField.setAccessible(true);
            cooldownTimeField.setAccessible(true);
            cooldownField.setFloat(weapon, value);
            cooldownTimeField.setFloat(weapon, 0f);
        } catch (Throwable t) {
            lastZeroCooldownStatus = "[快速开火] setCooldown 失败: " + (t.getMessage() != null ? t.getMessage() : "");
        }
    }

    static void applyInteractDistanceToggle(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Field gf = mainFrame.getClass().getDeclaredField("gameContext");
            gf.setAccessible(true);
            Object gameContext = gf.get(mainFrame);
            if (gameContext == null) return;
            Class<?> entityMapClass = Class.forName("Base.General.Container.EntityMap");
            Object interactiveObjectsEnum = Enum.valueOf((Class<Enum>) entityMapClass, "InteractiveObjects");
            Method getEntityMap = gameContext.getClass().getMethod("getEntityMap", entityMapClass);
            @SuppressWarnings("unchecked")
            HashSet<Object> ioSet = (HashSet<Object>) getEntityMap.invoke(gameContext, interactiveObjectsEnum);
            if (ioSet == null || ioSet.isEmpty()) return;
            Method getIOProps = Class.forName("Game.General.InteractiveObject").getMethod("getIOProps");
            Field interactDistance = Class.forName("Game.General.Serializable.IOProps").getField("interactDistance");
            for (Object io : ioSet) {
                Object ioProps = getIOProps.invoke(io);
                if (ioProps != null) interactDistance.setFloat(ioProps, 999f);
            }
        } catch (Throwable t) { }
    }

    static void applyMeleeAnimSpeed(Object mainFrame) {
        lastMeleeDebugStatus = "";
        if (mainFrame == null) return;
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) {
                lastMeleeDebugStatus = "[近战] ki=null";
                return;
            }
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object player = getActivePlayer.invoke(ki);
            if (player == null) {
                lastMeleeDebugStatus = "[近战] player=null";
                return;
            }
            Method getAnimState = player.getClass().getMethod("getAnimState");
            Object animState = getAnimState.invoke(player);
            Class<?> animStateClass = Class.forName("KI.Game.General.TPPlayer$AnimState");
            Object meleeState = Enum.valueOf((Class<Enum>) animStateClass, "MELEE");
            if (!animState.equals(meleeState)) {
                lastMeleeDebugStatus = "[近战] 状态=" + (animState != null ? animState.toString() : "null");
                return;
            }
            Method getAnimController = player.getClass().getMethod("getAnimController");
            Object animController = getAnimController.invoke(player);
            if (animController == null) {
                lastMeleeDebugStatus = "[近战] MELEE 但 animController=null";
                return;
            }
            Class<?> animCategoryClass = Class.forName("Animation.General.Resource.AnimationData$AnimCategory");
            Object actionCategory = Enum.valueOf((Class<Enum>) animCategoryClass, "ACTION");
            Method getAnim = animController.getClass().getMethod("getAnim", animCategoryClass, boolean.class);
            Object actionAnim = getAnim.invoke(animController, actionCategory, false);
            if (actionAnim == null) {
                lastMeleeDebugStatus = "[近战] MELEE 但 ACTION 动画=null";
                return;
            }
            Method isActive = actionAnim.getClass().getMethod("isActive");
            if (!Boolean.TRUE.equals(isActive.invoke(actionAnim))) {
                lastMeleeDebugStatus = "[近战] MELEE 但 ACTION 未 isActive";
                return;
            }
            Method setRate = actionAnim.getClass().getMethod("setRate", float.class);
            setRate.invoke(actionAnim, MELEE_ANIM_SPEED_MULTIPLIER);
            lastMeleeDebugStatus = "[近战] MELEE 已 setRate(" + MELEE_ANIM_SPEED_MULTIPLIER + ")";
        } catch (Throwable t) {
            lastMeleeDebugStatus = "[近战] 异常: " + t.getClass().getSimpleName() + " " + (t.getMessage() != null ? t.getMessage() : "");
        }
    }

    static void applyNoRecoil(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) return;
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object player = getActivePlayer.invoke(ki);
            if (player == null) return;
            Class<?> attrTypeClass = Class.forName("Game.Attributes.AttributeType");
            Object firearmRecoil = Enum.valueOf((Class<Enum>) attrTypeClass, "FIREARM_RECOIL_MULTIPLIER");
            Method setAttribute = player.getClass().getMethod("setAttribute", attrTypeClass, float.class, boolean.class);
            float value = GameAgent.getNoRecoilEnabled() ? 0f : 1f;
            setAttribute.invoke(player, firearmRecoil, value, false);
        } catch (Throwable t) { }
    }

    static void applyAlwaysSprint(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) return;
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object player = getActivePlayer.invoke(ki);
            if (player == null) return;
            Class<?> attrTypeClass = Class.forName("Game.Attributes.AttributeType");
            Object staminaMax = Enum.valueOf((Class<Enum>) attrTypeClass, "STAMINA_MAX");
            Method getAttribute = player.getClass().getMethod("getAttribute", attrTypeClass);
            float maxVal = ((Number) getAttribute.invoke(player, staminaMax)).floatValue();
            Object stamina = Enum.valueOf((Class<Enum>) attrTypeClass, "STAMINA");
            Method setAttribute = player.getClass().getMethod("setAttribute", attrTypeClass, float.class, boolean.class);
            setAttribute.invoke(player, stamina, maxVal, false);
        } catch (Throwable t) { }
    }

    static void applyPerfectBlock(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) return;
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object defender = getActivePlayer.invoke(ki);
            if (defender == null) return;
            Method getGrappleInfo = defender.getClass().getMethod("getGrappleInfo");
            Object grappleInfo = getGrappleInfo.invoke(defender);
            if (grappleInfo == null) return;
            Method isActive = grappleInfo.getClass().getMethod("isActive");
            Method isAttacker = grappleInfo.getClass().getMethod("isAttacker");
            Method canBlock = grappleInfo.getClass().getMethod("canBlock");
            if (!Boolean.TRUE.equals(isActive.invoke(grappleInfo)) || Boolean.TRUE.equals(isAttacker.invoke(grappleInfo)) || !Boolean.TRUE.equals(canBlock.invoke(grappleInfo))) return;
            Class<?> stateClass = Class.forName("KI.Game.General.GrappleInfo$GrappleState");
            Object stateAttack = Enum.valueOf((Class<Enum>) stateClass, "ATTACK");
            Method getState = grappleInfo.getClass().getMethod("getState");
            if (!stateAttack.equals(getState.invoke(grappleInfo))) return;
            Method getPairedLeader = defender.getClass().getMethod("getPairedLeader");
            Object attacker = getPairedLeader.invoke(defender);
            if (attacker == null) return;
            Method getAnimController = attacker.getClass().getMethod("getAnimController");
            Object animController = getAnimController.invoke(attacker);
            if (animController == null) return;
            Class<?> animCategoryClass = Class.forName("Animation.General.Resource.AnimationData$AnimCategory");
            Object categoryAction = Enum.valueOf((Class<Enum>) animCategoryClass, "ACTION");
            Method getAnim = animController.getClass().getMethod("getAnim", animCategoryClass, boolean.class);
            Object attackAnim = getAnim.invoke(animController, categoryAction, false);
            if (attackAnim == null) return;
            Field timeField = attackAnim.getClass().getDeclaredField("time");
            timeField.setAccessible(true);
            float animTime = timeField.getFloat(attackAnim);
            Object resource = attackAnim.getClass().getMethod("getResource").invoke(attackAnim);
            if (resource == null) return;
            Object animData = resource.getClass().getMethod("getAnimData").invoke(resource);
            if (animData == null) return;
            float fps = ((Number) animData.getClass().getMethod("getFps").invoke(animData)).floatValue();
            if (fps < 1f) return;
            Method getPunchFrame = grappleInfo.getClass().getMethod("getPunchFrame");
            int punchFrame = ((Number) getPunchFrame.invoke(grappleInfo)).intValue();
            float totalTime = punchFrame / fps;
            if (animTime > totalTime) return;
            Method getGrappleDifficulty = grappleInfo.getClass().getMethod("getGrappleDifficulty", attacker.getClass(), defender.getClass());
            Object difficulty = getGrappleDifficulty.invoke(grappleInfo, attacker, defender);
            if (difficulty == null) return;
            Field kiGameDataField = ki.getClass().getDeclaredField("kiGameData");
            kiGameDataField.setAccessible(true);
            Object kiGameData = kiGameDataField.get(ki);
            if (kiGameData == null) return;
            Field levelsField = kiGameData.getClass().getDeclaredField("grappleDifficultyLevels");
            levelsField.setAccessible(true);
            Object grappleDifficultyLevels = levelsField.get(kiGameData);
            if (grappleDifficultyLevels == null) return;
            Method getElementOnArray = grappleDifficultyLevels.getClass().getMethod("getElement", difficulty.getClass());
            Object difficultyInfo = getElementOnArray.invoke(grappleDifficultyLevels, difficulty);
            if (difficultyInfo == null) return;
            Field blockAllowanceField = difficultyInfo.getClass().getDeclaredField("blockAllowance");
            blockAllowanceField.setAccessible(true);
            Object blockAllowance = blockAllowanceField.get(difficultyInfo);
            if (blockAllowance == null) return;
            float minAmount = blockAllowance.getClass().getField("minAmount").getFloat(blockAllowance);
            float maxAmount = blockAllowance.getClass().getField("maxAmount").getFloat(blockAllowance);
            float earliestBlockTime = totalTime * minAmount;
            float latestBlockTime = totalTime * maxAmount;
            float minFrameAllowance = Math.min(0.05f, 1f / fps);
            float earliestBlockTime2 = latestBlockTime - Math.max(latestBlockTime - earliestBlockTime, minFrameAllowance);
            if (animTime < earliestBlockTime2 || animTime > latestBlockTime) return;
            Method getBlockInput = grappleInfo.getClass().getMethod("getBlockInput");
            Object blockInputAction = getBlockInput.invoke(grappleInfo);
            if (blockInputAction != null) {
                Method getInputManager = defender.getClass().getMethod("getInputManager");
                Object inputManager = getInputManager.invoke(defender);
                if (inputManager != null) {
                    Field actionsPressedField = inputManager.getClass().getDeclaredField("actionsPressed");
                    actionsPressedField.setAccessible(true);
                    boolean[] actionsPressed = (boolean[]) actionsPressedField.get(inputManager);
                    if (actionsPressed != null) {
                        int ordinal = ((Enum<?>) blockInputAction).ordinal();
                        if (ordinal >= 0 && ordinal < actionsPressed.length) actionsPressed[ordinal] = true;
                    }
                }
            }
            Class<?> grappleInfoClass = Class.forName("KI.Game.General.GrappleInfo");
            Method setBlockingPaired = grappleInfoClass.getMethod("setBlockingPaired", defender.getClass(), boolean.class);
            setBlockingPaired.invoke(null, defender, true);
        } catch (Throwable t) { }
    }

    static void applyVisionBypass(Object mainFrame) {
        if (mainFrame == null) {
            if (GameAgent.getVisionBypassEnabled()) lastVisionDebugStatus = "[红外透视] mainFrame=null";
            return;
        }
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) {
                if (GameAgent.getVisionBypassEnabled()) lastVisionDebugStatus = "[红外透视] ki=null（未进对局？）";
                return;
            }
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object player = getActivePlayer.invoke(ki);
            if (player == null) {
                if (GameAgent.getVisionBypassEnabled()) lastVisionDebugStatus = "[红外透视] player=null（未进对局或未生成角色）";
                return;
            }
            Class<?> attrTypeClass = Class.forName("Game.Attributes.AttributeType");
            Method setAttribute = player.getClass().getMethod("setAttribute", attrTypeClass, float.class, boolean.class);
            Object infrared = Enum.valueOf((Class<Enum>) attrTypeClass, "INFRARED");
            Object playerVision = Enum.valueOf((Class<Enum>) attrTypeClass, "PLAYER_VISION");
            if (GameAgent.getVisionBypassEnabled()) {
                setAttribute.invoke(player, infrared, 1f, false);
                setAttribute.invoke(player, playerVision, 1f, false);
                lastVisionDebugStatus = "[红外透视] OK";
            } else {
                setAttribute.invoke(player, infrared, 0f, false);
                setAttribute.invoke(player, playerVision, 0f, false);
                lastVisionDebugStatus = "";
            }
        } catch (Throwable t) {
            if (GameAgent.getVisionBypassEnabled()) lastVisionDebugStatus = "[红外透视] 异常: " + t.getClass().getSimpleName() + " " + (t.getMessage() != null ? t.getMessage() : "");
        }
    }

    static void applyFrontPickpocket(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Field gf = mainFrame.getClass().getDeclaredField("gameContext");
            gf.setAccessible(true);
            Object gameContext = gf.get(mainFrame);
            if (gameContext == null) return;
            Class<?> entityMapClass = Class.forName("Base.General.Container.EntityMap");
            Object interactiveObjectsEnum = Enum.valueOf((Class<Enum>) entityMapClass, "InteractiveObjects");
            Method getEntityMap = gameContext.getClass().getMethod("getEntityMap", entityMapClass);
            @SuppressWarnings("unchecked")
            HashSet<Object> ioSet = (HashSet<Object>) getEntityMap.invoke(gameContext, interactiveObjectsEnum);
            if (ioSet == null || ioSet.isEmpty()) return;
            Method getIOProps = Class.forName("Game.General.InteractiveObject").getMethod("getIOProps");
            Class<?> ioPropsClass = Class.forName("Game.General.Serializable.IOProps");
            Field checkFacingAngle = ioPropsClass.getDeclaredField("checkFacingAngle");
            Field checkPlayerAngle = ioPropsClass.getDeclaredField("checkPlayerAngle");
            Field activationAngleField = ioPropsClass.getDeclaredField("activationAngle");
            checkFacingAngle.setAccessible(true);
            checkPlayerAngle.setAccessible(true);
            activationAngleField.setAccessible(true);
            Class<?> floatRangeClass = Class.forName("Lib.Property.FloatRange");
            Field minAmount = floatRangeClass.getDeclaredField("minAmount");
            Field maxAmount = floatRangeClass.getDeclaredField("maxAmount");
            minAmount.setAccessible(true);
            maxAmount.setAccessible(true);
            for (Object io : ioSet) {
                Object ioProps = getIOProps.invoke(io);
                if (ioProps != null) {
                    checkFacingAngle.setBoolean(ioProps, false);
                    checkPlayerAngle.setBoolean(ioProps, false);
                    Object actAngle = activationAngleField.get(ioProps);
                    if (actAngle != null) {
                        minAmount.setFloat(actAngle, -180f);
                        maxAmount.setFloat(actAngle, 180f);
                    }
                }
            }
        } catch (Throwable t) { }
    }

    private static Object pickItemByStealPriority(List<?> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            Class<?> itemDataClass = Class.forName("KI.Game.General.Resource.KIItemData");
            Class<?> itemTypeClass = Class.forName("KI.Game.General.Resource.KIItemData$KIItemType");
            Method getData = Class.forName("KI.Game.General.KIItem").getMethod("getData");
            Method hasType = itemDataClass.getMethod("hasType", itemTypeClass);
            String[] prio = new String[]{ GameAgent.getStealPriority1(), GameAgent.getStealPriority2(), GameAgent.getStealPriority3() };
            java.util.List<Object> typeEnums = new java.util.ArrayList<>();
            for (String p : prio) {
                if (p == null || p.trim().isEmpty()) continue;
                try {
                    typeEnums.add(Enum.valueOf((Class<Enum>) itemTypeClass, p.trim()));
                } catch (Throwable ignored) { }
            }
            for (Object typeEnum : typeEnums) {
                for (Object item : items) {
                    if (item == null) continue;
                    Object data = getData.invoke(item);
                    if (data != null && Boolean.TRUE.equals(hasType.invoke(data, typeEnum))) {
                        return item;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return items.get(0);
    }

    static void applyPerfectPickpocketMinigame(Object mainFrame) {
        if (mainFrame == null) return;
        try {
            Method getContext = mainFrame.getClass().getMethod("getContext");
            Object context = getContext.invoke(mainFrame);
            if (context == null) return;
            Method getMenu = context.getClass().getMethod("getMenu");
            Method getNextMenu = context.getClass().getMethod("getNextMenu");
            Object menu = getMenu.invoke(context);
            if (menu == null) menu = getNextMenu.invoke(context);
            if (menu == null) {
                Field kf = mainFrame.getClass().getDeclaredField("ki");
                kf.setAccessible(true);
                Object ki = kf.get(mainFrame);
                if (ki != null) {
                    Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
                    Object player = getActivePlayer.invoke(ki);
                    if (player != null) {
                        Method getPlayerContext = player.getClass().getMethod("getContext");
                        Object playerContext = getPlayerContext.invoke(player);
                        if (playerContext != null) {
                            menu = getMenu.invoke(playerContext);
                            if (menu == null) menu = getNextMenu.invoke(playerContext);
                        }
                    }
                }
            }
            if (menu != null && menu.getClass().getName().contains("MinigameKIPickpocket")) {
                Field itemsField = menu.getClass().getDeclaredField("items");
                itemsField.setAccessible(true);
                List<?> items = (List<?>) itemsField.get(menu);
                if (items == null || items.isEmpty()) return;
                Object targetItem = pickItemByStealPriority(items);
                for (Class<?> c = menu.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Field infoField = c.getDeclaredField("info");
                        infoField.setAccessible(true);
                        Object info = infoField.get(menu);
                        if (info != null) {
                            try {
                                Field itemField = info.getClass().getDeclaredField("item");
                                itemField.setAccessible(true);
                                itemField.set(info, targetItem);
                            } catch (Throwable ignored) { }
                        }
                        break;
                    } catch (NoSuchFieldException ignored) { }
                }
                Field selectedItemField = menu.getClass().getDeclaredField("selectedItem");
                Field scoreField = menu.getClass().getDeclaredField("score");
                Field thiefUsedField = menu.getClass().getDeclaredField("thiefUsed");
                selectedItemField.setAccessible(true);
                scoreField.setAccessible(true);
                thiefUsedField.setAccessible(true);
                selectedItemField.set(menu, targetItem);
                scoreField.setFloat(menu, 5f);
                thiefUsedField.setBoolean(menu, true);
                Class<?> resultClass = Class.forName("UI.Menu.UIScreen$UIScreenResult");
                Object successEnum = Enum.valueOf((Class<Enum>) resultClass, "SUCCESS");
                Method setResult = menu.getClass().getMethod("setResult", resultClass, int.class);
                setResult.invoke(menu, successEnum, 0);
            }
        } catch (Throwable t) { }
    }

    static void applyAimbot(Object mainFrame) {
        aimbotAimHeld = false;
        if (mainFrame == null) {
            lastAimbotDebugStatus = "mainFrame=null";
            return;
        }
        if (!GameAgent.getAimbotEnabled()) {
            lastAimbotDebugStatus = "";
            return;
        }
        try {
            Field kf = mainFrame.getClass().getDeclaredField("ki");
            kf.setAccessible(true);
            Object ki = kf.get(mainFrame);
            if (ki == null) {
                lastAimbotDebugStatus = "ki=null";
                return;
            }
            Method getActivePlayer = ki.getClass().getMethod("getActivePlayer");
            Object localPlayer = getActivePlayer.invoke(ki);
            if (localPlayer == null) {
                lastAimbotDebugStatus = "localPlayer=null";
                return;
            }
            Method getInputManager = localPlayer.getClass().getMethod("getInputManager");
            Object input = getInputManager.invoke(localPlayer);
            if (input == null) {
                lastAimbotDebugStatus = "input=null";
                return;
            }
            Class<?> inputActionClass = Class.forName("Lib.Input.InputManager$InputAction");
            Object aimAction = Enum.valueOf((Class<Enum>) inputActionClass, "AIM");
            Method isActionHeld = input.getClass().getMethod("isActionHeld", inputActionClass);
            boolean aimHeld = Boolean.TRUE.equals(isActionHeld.invoke(input, aimAction));
            aimbotAimHeld = aimHeld;
            Field kiGameContextField = ki.getClass().getDeclaredField("kiGameContext");
            kiGameContextField.setAccessible(true);
            Object kiGameContext = kiGameContextField.get(ki);
            if (kiGameContext == null) {
                lastAimbotDebugStatus = "kiGameContext=null";
                return;
            }
            Class<?> playerRoleClass = Class.forName("KI.Game.General.TPPlayer$PlayerRole");
            Object rolePlayer = Enum.valueOf((Class<Enum>) playerRoleClass, "PLAYER");
            Class<?> triplexClass = Class.forName("Lib.Util.Util$Triplex");
            Object triplexOn = Enum.valueOf((Class<Enum>) triplexClass, "ON");
            Object triplexOff = Enum.valueOf((Class<Enum>) triplexClass, "OFF");
            Method getPlayers = kiGameContext.getClass().getMethod("getPlayers", playerRoleClass, triplexClass, triplexClass);
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> others = (java.util.ArrayList<Object>) getPlayers.invoke(kiGameContext, rolePlayer, triplexOn, triplexOff);
            if (others == null || others.isEmpty()) {
                lastAimbotDebugStatus = "无其他玩家";
                return;
            }
            Method isAlive = localPlayer.getClass().getMethod("isAlive");
            Method getCollisionCenter = localPlayer.getClass().getMethod("getCollisionCenter");
            Method getEyePosition = localPlayer.getClass().getMethod("getEyePosition");
            Method getLookRotation = localPlayer.getClass().getMethod("getLookRotation");
            Method setLookRotation = localPlayer.getClass().getMethod("setLookRotation", Class.forName("Lib.Math.Angle"));
            Class<?> vec3Class = Class.forName("Lib.Math.Vec3");
            Method vec3Sub = vec3Class.getMethod("sub", vec3Class, vec3Class);
            Method toAngle = vec3Class.getMethod("toAngle");
            Class<?> angleClass = Class.forName("Lib.Math.Angle");
            Method angleToVector = angleClass.getMethod("toVector");
            Method vec3Dot = vec3Class.getMethod("dot", vec3Class, vec3Class);
            Method vec3Length = vec3Class.getMethod("length");
            Method getAnimController = localPlayer.getClass().getMethod("getAnimController");
            Method getHumanRig = getAnimController.getReturnType().getMethod("getHumanRig");
            Method getBonePositionWorld = getAnimController.getReturnType().getMethod("getBonePositionWorld", String.class);
            String boneField = GameAgent.getAimbotSelectedBoneField();
            Object firstOther = null;
            Object humanRigForDiscovery = null;
            for (Object other : others) {
                if (other == null || other == localPlayer) continue;
                if (!Boolean.TRUE.equals(isAlive.invoke(other))) continue;
                if (firstOther == null) {
                    firstOther = other;
                    try {
                        Object ac = getAnimController.invoke(other);
                        if (ac != null) humanRigForDiscovery = getHumanRig.invoke(ac);
                    } catch (Throwable ignored) { }
                }
            }
            if (humanRigForDiscovery != null) {
                GameAgent.discoverHumanRigBoneFields(humanRigForDiscovery);
            }
            Object rayOrigin;
            Object rayForward;
            try {
                Method getContext = mainFrame.getClass().getMethod("getContext");
                Object context = getContext.invoke(mainFrame);
                if (context == null) throw new NoSuchMethodException("context=null");
                Method getCamera = context.getClass().getMethod("getCamera");
                Object camera = getCamera.invoke(context);
                if (camera != null) {
                    Method getCameraPosition = camera.getClass().getMethod("getPosition");
                    Method getCameraRotation = camera.getClass().getMethod("getRotation");
                    rayOrigin = getCameraPosition.invoke(camera);
                    Object camRot = getCameraRotation.invoke(camera);
                    rayForward = angleToVector.invoke(camRot);
                } else {
                    rayOrigin = getEyePosition.invoke(localPlayer);
                    rayForward = angleToVector.invoke(getLookRotation.invoke(localPlayer));
                }
            } catch (Throwable ignored) {
                rayOrigin = getEyePosition.invoke(localPlayer);
                rayForward = angleToVector.invoke(getLookRotation.invoke(localPlayer));
            }
            if (rayOrigin == null || rayForward == null) {
                lastAimbotDebugStatus = "rayOrigin/rayForward=null";
                return;
            }
            int fovDeg = GameAgent.getAimbotFovDegrees();
            if (fovDeg < 1) fovDeg = 1;
            if (fovDeg > 20) fovDeg = 20;
            final float minDot = (float) Math.cos(Math.toRadians(fovDeg));
            final float maxDist = GameAgent.getAimbotMaxDistance() > 0 ? GameAgent.getAimbotMaxDistance() : Float.MAX_VALUE;
            final boolean visibleOnly = GameAgent.getAimbotVisibleOnly();
            java.util.List<Object[]> candidates = new java.util.ArrayList<>();
            for (Object other : others) {
                if (other == null || other == localPlayer) continue;
                if (!Boolean.TRUE.equals(isAlive.invoke(other))) continue;
                Object targetPos = getBonePosition(other, getAnimController, getHumanRig, getBonePositionWorld, boneField);
                if (targetPos == null) continue;
                Object dir = vec3Sub.invoke(null, targetPos, rayOrigin);
                if (dir == null) continue;
                Float len = (Float) vec3Length.invoke(dir);
                if (len == null || len < 0.001f) continue;
                if (len > maxDist) continue;
                Float rawDot = (Float) vec3Dot.invoke(null, rayForward, dir);
                if (rawDot == null) continue;
                float dot = rawDot / len;
                if (dot < minDot) continue;
                if (visibleOnly && !isTargetVisible(localPlayer, other, rayOrigin, targetPos)) continue;
                candidates.add(new Object[]{other, targetPos, len, dot});
            }
            Object bestTarget = null;
            Object bestTargetPos = null;
            if (!candidates.isEmpty()) {
                candidates.sort((a, b) -> Float.compare((Float) b[3], (Float) a[3]));
                Object[] first = candidates.get(0);
                bestTarget = first[0];
                bestTargetPos = first[1];
            }
            if (aimHeld && bestTarget != null && bestTargetPos != null) {
                Object dir = vec3Sub.invoke(null, bestTargetPos, rayOrigin);
                if (dir != null) {
                    Object aimAngle;
                    if (GameAgent.getAimbotSmoothingPercent() <= 0) {
                        aimAngle = toAngle.invoke(dir);
                    } else {
                        Object lerped = vec3Lerp(rayForward, dir, (100 - GameAgent.getAimbotSmoothingPercent()) / 100f);
                        aimAngle = lerped != null ? toAngle.invoke(lerped) : toAngle.invoke(dir);
                    }
                    if (aimAngle != null) setLookRotation.invoke(localPlayer, aimAngle);
                }
            }
            if (bestTarget != null) {
                aimbotLockedTargetRef = bestTarget;
                aimbotLockedTargetPos = bestTargetPos;
                aimbotLockedTargetName = GameAgentGameAccess.getPlayerDisplayName(bestTarget);
                aimbotLockedTargetPlayerName = GameAgentGameAccess.getPlayerName(bestTarget);
            } else {
                aimbotLockedTargetRef = null;
                aimbotLockedTargetPos = null;
                aimbotLockedTargetName = "";
                aimbotLockedTargetPlayerName = "";
            }
            StringBuilder dbg = new StringBuilder();
            dbg.append("按住右键: ").append(aimHeld ? "是" : "否").append(" | 锁定: ").append(bestTarget != null ? "有" : "无").append(" | 骨骼: ").append(boneField)
                    .append(" | FOV=").append(fovDeg).append("° minDot=").append(String.format("%.2f", minDot))
                    .append(" | 平滑: ").append(GameAgent.getAimbotSmoothingPercent()).append("%\n");
            dbg.append("射线起点: ").append(GameAgentGameAccess.formatVec3(rayOrigin)).append(" (相机/眼)\n");
            if (firstOther != null) {
                Object collisionCenter = getCollisionCenter.invoke(firstOther);
                dbg.append("getCollisionCenter() = ").append(GameAgentGameAccess.formatVec3(collisionCenter)).append("\n");
                Object bonePos = getBonePosition(firstOther, getAnimController, getHumanRig, getBonePositionWorld, boneField);
                if (bonePos != null) {
                    dbg.append(boneField).append(" = ").append(GameAgentGameAccess.formatVec3(bonePos)).append("\n");
                    dbg.append("躯干获取: 成功");
                } else {
                    dbg.append("躯干获取: 失败");
                }
            }
            lastAimbotDebugStatus = dbg.toString();
        } catch (Throwable t) {
            lastAimbotDebugStatus = "applyAimbot 异常: " + t.getClass().getSimpleName() + " " + (t.getMessage() != null ? t.getMessage() : "");
        }
    }

    private static Object getBonePosition(Object targetPlayer, Method getAnimController, Method getHumanRig, Method getBonePositionWorld, String boneFieldName) {
        try {
            Object animCtrl = getAnimController.invoke(targetPlayer);
            if (animCtrl == null) return null;
            Object humanRig = getHumanRig.invoke(animCtrl);
            if (humanRig == null) return null;
            java.lang.reflect.Field jointField = humanRig.getClass().getDeclaredField(boneFieldName);
            jointField.setAccessible(true);
            Object joint = jointField.get(humanRig);
            if (joint == null) return null;
            java.lang.reflect.Field bn = joint.getClass().getDeclaredField("boneName");
            bn.setAccessible(true);
            String boneName = (String) bn.get(joint);
            if (boneName == null || boneName.isEmpty()) return null;
            return getBonePositionWorld.invoke(animCtrl, boneName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isTargetVisible(Object localPlayer, Object target, Object eyePos, Object targetPos) {
        try {
            Method getVisibilityInfo = localPlayer.getClass().getMethod("getVisibilityInfo", localPlayer.getClass());
            Object info = getVisibilityInfo.invoke(localPlayer, target);
            if (info != null) {
                java.lang.reflect.Field f = info.getClass().getField("realtimeVisible");
                return Boolean.TRUE.equals(f.getBoolean(info));
            }
        } catch (ReflectiveOperationException ignored) { }
        try {
            Method m = target.getClass().getMethod("isVisible");
            return Boolean.TRUE.equals(m.invoke(target));
        } catch (ReflectiveOperationException ignored) { }
        try {
            Method m = localPlayer.getClass().getMethod("canSee", target.getClass());
            return Boolean.TRUE.equals(m.invoke(localPlayer, target));
        } catch (ReflectiveOperationException ignored) { }
        try {
            Method m = localPlayer.getClass().getMethod("isVisible", target.getClass());
            return Boolean.TRUE.equals(m.invoke(localPlayer, target));
        } catch (ReflectiveOperationException ignored) { }
        return true;
    }

    private static Object vec3Lerp(Object va, Object vb, float t) {
        if (va == null || vb == null || t >= 1f) return vb;
        if (t <= 0f) return va;
        try {
            Class<?> vc = va.getClass();
            Method getUnitVector = vc.getMethod("getUnitVector");
            Object vbUnit = getUnitVector.invoke(vb);
            if (vbUnit == null) return fallbackVec3Lerp(va, vb, t);
            Method lerpStatic = vc.getMethod("lerp", vc, vc, float.class);
            Object result = lerpStatic.invoke(null, va, vbUnit, t);
            if (result == null) return fallbackVec3Lerp(va, vb, t);
            Method normalize = vc.getMethod("normalize");
            normalize.invoke(result);
            return result;
        } catch (Throwable ignored) {
            return fallbackVec3Lerp(va, vb, t);
        }
    }

    private static Object fallbackVec3Lerp(Object va, Object vb, float t) {
        float[] af = GameAgentGameAccess.vec3ToFloats(va);
        float[] bf = GameAgentGameAccess.vec3ToFloats(vb);
        if (af == null || bf == null || af.length < 3 || bf.length < 3) return null;
        float len = (float) Math.sqrt((double) bf[0] * bf[0] + (double) bf[1] * bf[1] + (double) bf[2] * bf[2]);
        if (len < 1e-6f) return va;
        float bx = bf[0] / len, by = bf[1] / len, bz = bf[2] / len;
        float rx = af[0] + (bx - af[0]) * t, ry = af[1] + (by - af[1]) * t, rz = af[2] + (bz - af[2]) * t;
        float rlen = (float) Math.sqrt((double) rx * rx + (double) ry * ry + (double) rz * rz);
        if (rlen < 1e-6f) return va;
        rx /= rlen; ry /= rlen; rz /= rlen;
        try {
            Class<?> vc = va.getClass();
            java.lang.reflect.Constructor<?> c = vc.getConstructor(float.class, float.class, float.class);
            return c.newInstance(rx, ry, rz);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
