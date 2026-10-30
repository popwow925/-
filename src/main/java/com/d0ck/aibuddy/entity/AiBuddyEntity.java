package com.d0ck.aibuddy.entity;

import com.d0ck.aibuddy.AiBuddyConfig;
import com.d0ck.aibuddy.AiBuddyMod;
import com.d0ck.aibuddy.ai.BuddyBrain;
import com.d0ck.aibuddy.task.BuddyTask;
import com.d0ck.aibuddy.task.BuildTask;
import com.d0ck.aibuddy.task.MineTask;
import com.d0ck.aibuddy.task.SimpleTasks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.AbstractPiglinEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 伙伴：玩家外观的生物。本地代码负责每 tick 的走路、战斗、挖掘、建造；
 * {@link BuddyBrain} 负责调用大模型决定“说什么、做什么”。
 */
public class AiBuddyEntity extends PathAwareEntity {
    public enum Mode { FOLLOW, STAY }

    private final SimpleInventory inventory = new SimpleInventory(27);
    private final BuddyBrain brain = new BuddyBrain(this);
    private final Deque<BuddyTask> tasks = new ArrayDeque<>();
    private final Set<UUID> ignoredItems = new HashSet<>();

    private UUID ownerUuid;
    private Mode mode = Mode.FOLLOW;
    private boolean guard = true;

    private BlockPos breakingPos;
    private float breakProgress;
    private int eatCooldown;
    private boolean wasNight;
    private float lastOwnerHealth = 20;
    private float lastSelfHealth = 20;

    public AiBuddyEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.setPersistent();
        this.setCustomNameVisible(true);
        for (EquipmentSlot slot : EquipmentSlot.values()) this.setEquipmentDropChance(slot, 2.0f);
    }

    public static DefaultAttributeContainer.Builder createBuddyAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.25, true));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(9, new LookAroundGoal(this));
        this.targetSelector.add(1, new RevengeGoal(this, PlayerEntity.class, AiBuddyEntity.class));
    }

    // ------------------------------------------------------------------ 基本信息

    public BuddyBrain brain() { return brain; }

    public SimpleInventory getInventory() { return inventory; }

    public void setOwner(PlayerEntity player) {
        this.ownerUuid = player.getUuid();
        refreshName();
    }

    public void refreshName() {
        this.setCustomName(Text.literal(AiBuddyConfig.get().buddyName));
    }

    public ServerPlayerEntity getOwnerPlayer() {
        MinecraftServer server = getServer();
        if (server == null || ownerUuid == null) return null;
        return server.getPlayerManager().getPlayer(ownerUuid);
    }

    public void say(String msg) {
        MinecraftServer server = getServer();
        if (server == null || msg == null || msg.isBlank()) return;
        server.getPlayerManager().broadcast(
                Text.literal("<" + AiBuddyConfig.get().buddyName + "> ").formatted(Formatting.AQUA)
                        .append(Text.literal(msg).formatted(Formatting.WHITE)), false);
    }

    public void sayError(String msg) {
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner != null) owner.sendMessage(Text.literal("[AI伙伴] " + msg).formatted(Formatting.RED));
    }

    public void giveStarterKit() {
        this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        inventory.addStack(new ItemStack(Items.BREAD, 8));
        refreshName();
    }

    public void teleportNear(PlayerEntity p) {
        getNavigation().stop();
        Vec3d back = p.getRotationVector().multiply(-1.5, 0, -1.5);
        this.requestTeleport(p.getX() + back.x, p.getY(), p.getZ() + back.z);
    }

    public String describeState() {
        BuddyTask t = tasks.peekFirst();
        return (mode == Mode.FOLLOW ? "跟随" : "待命") + "，护卫" + (guard ? "开" : "关")
                + "，生命 " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth())
                + (t != null ? "，" + t.describe() : "");
    }

    // ------------------------------------------------------------------ 主循环

    @Override
    public void tick() {
        super.tick();
        World world = getWorld();
        if (world.isClient) return;

        // 同一主人只保留一个伙伴：重复的（比如区块重新加载出来的旧伙伴）把东西合并过去
        if (ownerUuid != null) {
            AiBuddyEntity cur = AiBuddyMod.getActive(ownerUuid);
            if (cur == null) {
                AiBuddyMod.ACTIVE.put(ownerUuid, this);
            } else if (cur != this) {
                mergeInto(cur);
                discard();
                return;
            }
        }

        ServerPlayerEntity owner = getOwnerPlayer();
        boolean ownerHere = owner != null && owner.getWorld() == world && !owner.isSpectator();

        if (age % 5 == 0) pickUpNearbyItems();
        if (age % 40 == 0) autoEquipArmor();
        if (age % 100 == 0 && getHealth() < getMaxHealth()) heal(1.0f);
        if (eatCooldown > 0) eatCooldown--;
        if (getHealth() <= getMaxHealth() - 6) tryEat(false);
        if (age % 200 == 0) ignoredItems.clear();

        // 目标管理
        LivingEntity target = getTarget();
        if (target != null && (!target.isAlive() || squaredDistanceTo(target) > 24 * 24
                || (ownerHere && squaredDistanceTo(owner) > 28 * 28))) {
            setTarget(null);
            target = null;
        }
        if (guard && target == null && ownerHere && age % 10 == 0) {
            LivingEntity threat = findThreat(owner);
            if (threat != null) {
                equipBestWeapon();
                setTarget(threat);
                target = threat;
            }
        }

        // 任务
        BuddyTask task = tasks.peekFirst();
        if (task != null) {
            if (target == null || !task.pausesInCombat()) {
                if (task.tick(this)) {
                    tasks.pollFirst();
                    resetBreaking();
                    String r = task.result();
                    if (r != null) {
                        if (AiBuddyConfig.get().hasKey()) brain.onTaskReport(r);
                        else say(r);
                    }
                }
            }
        } else if (ownerHere && target == null) {
            followOwner(owner);
        }

        if (ownerHere) detectEvents(owner);
    }

    private void followOwner(ServerPlayerEntity owner) {
        double d = squaredDistanceTo(owner);
        if (d > 32 * 32) {
            teleportNear(owner);
            return;
        }
        if (mode != Mode.FOLLOW) return;
        if (d < 2.5 * 2.5) {
            getNavigation().stop();
        } else if (age % 10 == 0 && (d > 5 * 5 || (d > 3 * 3 && getNavigation().isIdle()))) {
            getNavigation().startMovingTo(owner, d > 12 * 12 ? 1.4 : 1.15);
        }
    }

    private LivingEntity findThreat(ServerPlayerEntity owner) {
        LivingEntity attacker = owner.getAttacker();
        if (attacker != null && attacker.isAlive() && !(attacker instanceof PlayerEntity)
                && attacker != this && squaredDistanceTo(attacker) < 16 * 16) {
            return attacker;
        }
        List<HostileEntity> list = getWorld().getEntitiesByClass(HostileEntity.class,
                owner.getBoundingBox().expand(10, 4, 10).union(getBoundingBox().expand(8, 4, 8)),
                e -> e.isAlive() && !(e instanceof AbstractPiglinEntity)
                        && !(e instanceof Angerable a && !a.hasAngerTime()));
        return list.stream().min(Comparator.comparingDouble(this::squaredDistanceTo)).orElse(null);
    }

    private void detectEvents(ServerPlayerEntity owner) {
        boolean night = getWorld().isNight();
        if (night && !wasNight && getWorld().getRegistryKey() == World.OVERWORLD) {
            brain.onEvent("天黑了，怪物要出来了。");
        }
        wasNight = night;

        float oh = owner.getHealth();
        if (oh <= 6 && lastOwnerHealth > 6) brain.onEvent("主人生命值很低（" + Math.round(oh) + "/20），很危险！");
        lastOwnerHealth = oh;

        float sh = getHealth();
        if (sh <= 6 && lastSelfHealth > 6) brain.onEvent("你自己生命值很低（" + Math.round(sh) + "/20）。");
        lastSelfHealth = sh;
    }

    // ------------------------------------------------------------------ AI 动作

    public void executeAction(JsonObject o) {
        String act = str(o, "do", str(o, "action", str(o, "type", ""))).toLowerCase();
        ServerPlayerEntity owner = getOwnerPlayer();
        switch (act) {
            case "follow" -> mode = Mode.FOLLOW;
            case "stay" -> {
                mode = Mode.STAY;
                getNavigation().stop();
            }
            case "come" -> {
                mode = Mode.FOLLOW;
                if (owner != null && owner.getWorld() == getWorld()) {
                    if (squaredDistanceTo(owner) > 16 * 16) teleportNear(owner);
                    else getNavigation().startMovingTo(owner, 1.3);
                }
            }
            case "goto" -> {
                mode = Mode.STAY;
                tasks.addLast(new SimpleTasks.Goto(BlockPos.ofFloored(num(o, "x", getX()), num(o, "y", getY()), num(o, "z", getZ()))));
            }
            case "guard" -> {
                guard = bool(o, "on", true);
                if (!guard) setTarget(null);
            }
            case "attack" -> tasks.addLast(new SimpleTasks.Attack(str(o, "target", "zombie")));
            case "mine" -> tasks.addLast(new MineTask(str(o, "block", "log"), (int) num(o, "count", 8)));
            case "build" -> {
                if (owner == null) return;
                String shape = str(o, "shape", "house").toLowerCase();
                List<BlockPos> pos = BuildTask.shape(shape, owner,
                        (int) num(o, "w", 5), (int) num(o, "h", shape.equals("pillar") ? 5 : 4), (int) num(o, "d", 5));
                String label = switch (shape) {
                    case "wall" -> "砌墙";
                    case "floor" -> "铺地板";
                    case "pillar" -> "搭柱子";
                    default -> "盖房子";
                };
                tasks.addLast(new BuildTask(label, str(o, "block", null), pos));
            }
            case "place" -> {
                List<BlockPos> list = new ArrayList<>();
                if (o.has("positions") && o.get("positions").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("positions")) {
                        if (!e.isJsonArray()) continue;
                        JsonArray a = e.getAsJsonArray();
                        if (a.size() < 3) continue;
                        list.add(BlockPos.ofFloored(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()));
                        if (list.size() >= 256) break;
                    }
                }
                if (!list.isEmpty()) tasks.addLast(new BuildTask("放方块", str(o, "block", null), list));
            }
            case "give" -> tasks.addLast(new SimpleTasks.Give(str(o, "item", "all"), (int) num(o, "count", 0)));
            case "drop" -> dropItems(str(o, "item", "all"), (int) num(o, "count", 0));
            case "collect" -> tasks.addLast(new SimpleTasks.Collect());
            case "equip" -> {
                autoEquipArmor();
                equipBestWeapon();
            }
            case "eat" -> tryEat(true);
            case "stop" -> {
                tasks.clear();
                getNavigation().stop();
                setTarget(null);
                resetBreaking();
            }
            default -> AiBuddyMod.LOGGER.debug("未知动作: {}", o);
        }
    }

    private static String str(JsonObject o, String k, String def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static double num(JsonObject o, String k, double def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 观察（发给 AI 的状态）

    public String observe() {
        World w = getWorld();
        StringBuilder sb = new StringBuilder();
        BlockPos p = getBlockPos();
        String biome = w.getBiome(p).getKey().map(k -> k.getValue().getPath()).orElse("?");
        sb.append("你的位置: ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ())
                .append("（维度 ").append(w.getRegistryKey().getValue().getPath()).append("，群系 ").append(biome).append("）\n");
        sb.append("你的生命: ").append(Math.round(getHealth())).append("/").append(Math.round(getMaxHealth()))
                .append("，模式: ").append(mode == Mode.FOLLOW ? "跟随" : "待命")
                .append("，护卫: ").append(guard ? "开" : "关").append('\n');
        sb.append("手持: ").append(itemName(getMainHandStack())).append("；盔甲: ");
        List<String> armor = new ArrayList<>();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack a = getEquippedStack(s);
            if (!a.isEmpty()) armor.add(itemName(a));
        }
        sb.append(armor.isEmpty() ? "无" : String.join(", ", armor)).append('\n');

        Map<String, Integer> inv = new LinkedHashMap<>();
        for (ItemStack s : inventory.getHeldStacks()) {
            if (!s.isEmpty()) inv.merge(itemName(s), s.getCount(), Integer::sum);
        }
        sb.append("背包: ");
        if (inv.isEmpty()) sb.append("空");
        else inv.forEach((k, v) -> sb.append(k).append('×').append(v).append(' '));
        sb.append('\n');

        BuddyTask t = tasks.peekFirst();
        sb.append("当前任务: ").append(t == null ? "无" : t.describe());
        if (tasks.size() > 1) sb.append("（后面还排着 ").append(tasks.size() - 1).append(" 个）");
        sb.append('\n');

        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner != null) {
            BlockPos op = owner.getBlockPos();
            sb.append("主人 ").append(owner.getName().getString()).append(": 位置 ")
                    .append(op.getX()).append(' ').append(op.getY()).append(' ').append(op.getZ());
            if (owner.getWorld() == w) sb.append("，距离你 ").append(Math.round(Math.sqrt(squaredDistanceTo(owner)))).append(" 格");
            else sb.append("（在另一个维度）");
            sb.append("，生命 ").append(Math.round(owner.getHealth())).append("/20，饥饿 ")
                    .append(owner.getHungerManager().getFoodLevel()).append("/20，手持 ")
                    .append(itemName(owner.getMainHandStack())).append('\n');
        }

        long tod = w.getTimeOfDay() % 24000;
        sb.append("时间: ").append(w.isNight() ? "夜晚" : "白天").append("（").append(tod).append("）")
                .append("，天气: ").append(w.isThundering() ? "雷暴" : w.isRaining() ? "下雨" : "晴").append('\n');

        Map<String, Integer> mobs = new LinkedHashMap<>();
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(16), e -> e != this && e.isAlive())) {
            String name = e instanceof PlayerEntity pl ? "玩家" + pl.getName().getString()
                    : Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            mobs.merge(name, 1, Integer::sum);
        }
        sb.append("附近 16 格生物: ");
        if (mobs.isEmpty()) sb.append("无");
        else mobs.forEach((k, v) -> sb.append(k).append('×').append(v).append(' '));
        return sb.toString();
    }

    private static String itemName(ItemStack s) {
        return s.isEmpty() ? "空手" : Registries.ITEM.getId(s.getItem()).getPath();
    }

    // ------------------------------------------------------------------ 背包 / 装备

    private void pickUpNearbyItems() {
        List<ItemEntity> items = getWorld().getEntitiesByClass(ItemEntity.class, getBoundingBox().expand(1.5, 0.5, 1.5),
                e -> e.isAlive() && !e.cannotPickup() && !ignoredItems.contains(e.getUuid()));
        for (ItemEntity ie : items) {
            ItemStack stack = ie.getStack();
            if (!inventory.canInsert(stack)) continue;
            int before = stack.getCount();
            ItemStack rest = inventory.addStack(stack.copy());
            int taken = before - rest.getCount();
            if (taken <= 0) continue;
            sendPickup(ie, taken);
            if (rest.isEmpty()) ie.discard();
            else ie.setStack(rest);
        }
    }

    private void autoEquipArmor() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!(s.getItem() instanceof ArmorItem ai)) continue;
            EquipmentSlot slot = ai.getType().getEquipmentSlot();
            ItemStack cur = getEquippedStack(slot);
            if (armorScore(s) > armorScore(cur)) {
                inventory.setStack(i, cur);
                equipStack(slot, s);
                setEquipmentDropChance(slot, 2.0f);
            }
        }
    }

    private static float armorScore(ItemStack s) {
        if (s.getItem() instanceof ArmorItem ai) return ai.getProtection() * 10 + ai.getToughness();
        return -1;
    }

    private static float weaponScore(ItemStack s) {
        if (s.getItem() instanceof SwordItem sw) return 100 + sw.getMaterial().getAttackDamage();
        if (s.getItem() instanceof AxeItem ax) return 50 + ax.getMaterial().getAttackDamage();
        if (s.getItem() instanceof ToolItem t) return 10 + t.getMaterial().getAttackDamage();
        return 0;
    }

    public void equipBestWeapon() {
        int best = -1;
        float bestScore = weaponScore(getMainHandStack());
        for (int i = 0; i < inventory.size(); i++) {
            float sc = weaponScore(inventory.getStack(i));
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best >= 0) swapIntoHand(best);
    }

    private void equipBestToolFor(BlockState st) {
        int best = -1;
        float bestScore = toolScore(getMainHandStack(), st);
        for (int i = 0; i < inventory.size(); i++) {
            float sc = toolScore(inventory.getStack(i), st);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best >= 0) swapIntoHand(best);
    }

    private static float toolScore(ItemStack s, BlockState st) {
        if (s.isEmpty()) return 1;
        return (s.isSuitableFor(st) ? 1000 : 0) + s.getMiningSpeedMultiplier(st);
    }

    private void swapIntoHand(int slot) {
        ItemStack inHand = getMainHandStack();
        ItemStack chosen = inventory.getStack(slot);
        inventory.setStack(slot, inHand);
        equipStack(EquipmentSlot.MAINHAND, chosen);
        setEquipmentDropChance(EquipmentSlot.MAINHAND, 2.0f);
    }

    public boolean hasToolFor(BlockState st) {
        if (getMainHandStack().isSuitableFor(st)) return true;
        for (ItemStack s : inventory.getHeldStacks()) if (s.isSuitableFor(st)) return true;
        return false;
    }

    public void tryEat(boolean force) {
        if (eatCooldown > 0 || (!force && getHealth() >= getMaxHealth())) return;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            FoodComponent food = s.get(DataComponentTypes.FOOD);
            if (food == null || s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.POISONOUS_POTATO)) continue;
            heal(Math.max(2, food.nutrition()));
            s.decrement(1);
            eatCooldown = 40;
            getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            return;
        }
        if (force) say("我背包里没吃的了……");
    }

    public int giveToOwner(String query, int count) {
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner == null) return 0;
        int left = count, given = 0;
        for (int i = 0; i < inventory.size() && left > 0; i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty() || !itemMatches(s, query)) continue;
            ItemStack part = s.split(Math.min(left, s.getCount()));
            int n = part.getCount();
            if (!owner.giveItemStack(part) && !part.isEmpty()) owner.dropItem(part, false);
            given += n;
            left -= n;
        }
        if (given > 0) {
            swingHand(Hand.MAIN_HAND);
            getWorld().playSound(null, owner.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.3f, 1.4f);
        }
        return given;
    }

    private void dropItems(String query, int count) {
        int left = count <= 0 ? Integer.MAX_VALUE : count;
        for (int i = 0; i < inventory.size() && left > 0; i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty() || !itemMatches(s, query)) continue;
            ItemStack part = s.split(Math.min(left, s.getCount()));
            left -= part.getCount();
            ItemEntity ie = dropStack(part);
            if (ie != null) ignoredItems.add(ie.getUuid());
        }
    }

    private static boolean itemMatches(ItemStack s, String query) {
        if (query == null || query.equalsIgnoreCase("all") || query.equals("全部")) return true;
        if (query.equalsIgnoreCase("food")) return s.get(DataComponentTypes.FOOD) != null;
        return BuddyTask.matches(Registries.ITEM.getId(s.getItem()).getPath(), query);
    }

    public void dropEverything() {
        World w = getWorld();
        if (w.isClient) return;
        ItemScatterer.spawn(w, this, inventory);
        inventory.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = getEquippedStack(slot);
            if (!s.isEmpty()) {
                dropStack(s.copy());
                equipStack(slot, ItemStack.EMPTY);
            }
        }
    }

    public void mergeInto(AiBuddyEntity other) {
        List<ItemStack> all = new ArrayList<>(inventory.getHeldStacks());
        for (EquipmentSlot slot : EquipmentSlot.values()) all.add(getEquippedStack(slot).copy());
        for (ItemStack s : all) {
            if (s.isEmpty()) continue;
            ItemStack rest = other.inventory.addStack(s.copy());
            if (!rest.isEmpty()) dropStack(rest);
        }
        inventory.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) equipStack(slot, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------ 挖掘 / 建造

    public void resetBreaking() {
        if (breakingPos != null) getWorld().setBlockBreakingInfo(getId(), breakingPos, -1);
        breakingPos = null;
        breakProgress = 0;
    }

    /** 不挖箱子、床等带方块实体的东西，不挖基岩/黑曜石级别的硬方块和液体 */
    public boolean canBreak(BlockPos pos) {
        World w = getWorld();
        BlockState st = w.getBlockState(pos);
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        if (st.getBlock() instanceof BlockEntityProvider) return false;
        float h = st.getHardness(w, pos);
        if (h < 0 || h >= 50) return false;
        return !st.isToolRequired() || hasToolFor(st);
    }

    /** 从眼睛到目标之间第一个挡路的方块 */
    public BlockPos findObstacle(BlockPos target) {
        Vec3d from = getEyePos();
        Vec3d to = Vec3d.ofCenter(target);
        BlockHitResult hit = getWorld().raycast(new RaycastContext(from, to,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(target)) return hit.getBlockPos();
        return null;
    }

    /** 推进一次挖掘，挖掉时返回 true */
    public boolean mineStep(BlockPos pos) {
        World w = getWorld();
        BlockState st = w.getBlockState(pos);
        if (st.isAir()) {
            resetBreaking();
            return true;
        }
        if (!pos.equals(breakingPos)) {
            resetBreaking();
            breakingPos = pos.toImmutable();
            equipBestToolFor(st);
        }
        getLookControl().lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        float hardness = st.getHardness(w, pos);
        if (hardness < 0) {
            resetBreaking();
            return false;
        }
        ItemStack tool = getMainHandStack();
        boolean canHarvest = !st.isToolRequired() || tool.isSuitableFor(st);
        float speed = Math.max(1f, tool.getMiningSpeedMultiplier(st));
        if (isSubmergedInWater()) speed /= 5f;
        if (!isOnGround()) speed /= 5f;
        breakProgress += hardness == 0 ? 1f : speed / hardness / (canHarvest ? 30f : 100f);
        if (age % 4 == 0) swingHand(Hand.MAIN_HAND);
        w.setBlockBreakingInfo(getId(), pos, Math.min(9, (int) (breakProgress * 10f)));
        if (breakProgress < 1f) return false;

        if (canHarvest) Block.dropStacks(st, w, pos, w.getBlockEntity(pos), this, tool);
        w.breakBlock(pos, false, this);
        if (!tool.isEmpty() && hardness > 0) tool.damage(1, this, EquipmentSlot.MAINHAND);
        resetBreaking();
        return true;
    }

    private int findBuildingSlot(String query, BlockPos at) {
        int best = -1, bestCount = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!(s.getItem() instanceof BlockItem bi)) continue;
            if (query != null) {
                if (BuddyTask.matches(Registries.ITEM.getId(s.getItem()).getPath(), query)) return i;
                continue;
            }
            Block b = bi.getBlock();
            if (b instanceof BlockEntityProvider) continue;
            if (!b.getDefaultState().isFullCube(getWorld(), at)) continue;
            if (s.getCount() > bestCount) {
                bestCount = s.getCount();
                best = i;
            }
        }
        return best;
    }

    public boolean hasBuildingBlock(String query, BlockPos at) {
        return findBuildingSlot(query, at) >= 0;
    }

    public boolean placeBuildingBlock(String query, BlockPos pos) {
        int slot = findBuildingSlot(query, pos);
        if (slot < 0) return false;
        ItemStack s = inventory.getStack(slot);
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        World w = getWorld();
        BlockState state = bi.getBlock().getDefaultState();
        if (!state.canPlaceAt(w, pos)) return false;
        getLookControl().lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (!w.setBlockState(pos, state)) return false;
        w.playSound(null, pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.9f);
        swingHand(Hand.MAIN_HAND);
        s.decrement(1);
        return true;
    }

    public void stepAwayFrom(BlockPos pos) {
        Vec3d away = getPos().subtract(Vec3d.ofCenter(pos));
        if (away.horizontalLengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        away = away.multiply(1, 0, 1).normalize().multiply(2.5);
        getNavigation().startMovingTo(getX() + away.x, getY(), getZ() + away.z, 1.2);
    }

    // ------------------------------------------------------------------ 交互 / 伤害 / 存档

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (ownerUuid != null && player.getUuid().equals(ownerUuid)) {
            if (!getWorld().isClient) {
                player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                        (syncId, playerInv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, inventory),
                        Text.literal(AiBuddyConfig.get().buddyName + " 的背包")));
            }
            return ActionResult.success(getWorld().isClient);
        }
        return super.interactMob(player, hand);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (ownerUuid != null && source.getAttacker() instanceof PlayerEntity p && p.getUuid().equals(ownerUuid)) {
            return false; // 主人误伤无效
        }
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (!getWorld().isClient) {
            ItemScatterer.spawn(getWorld(), this, inventory);
            inventory.clear();
            ServerPlayerEntity owner = getOwnerPlayer();
            if (owner != null) {
                owner.sendMessage(Text.literal("[AI伙伴] " + AiBuddyConfig.get().buddyName
                        + " 倒下了，东西掉在原地。用 /aibuddy summon 再叫一个。").formatted(Formatting.RED));
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (ownerUuid != null) AiBuddyMod.ACTIVE.remove(ownerUuid, this);
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerUuid != null) nbt.putUuid("BuddyOwner", ownerUuid);
        nbt.putString("BuddyMode", mode.name());
        nbt.putBoolean("BuddyGuard", guard);
        nbt.put("BuddyInventory", inventory.toNbtList(getWorld().getRegistryManager()));
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("BuddyOwner")) ownerUuid = nbt.getUuid("BuddyOwner");
        try {
            mode = Mode.valueOf(nbt.getString("BuddyMode"));
        } catch (Exception ignored) {
            mode = Mode.FOLLOW;
        }
        guard = !nbt.contains("BuddyGuard") || nbt.getBoolean("BuddyGuard");
        inventory.readNbtList(nbt.getList("BuddyInventory", NbtElement.COMPOUND_TYPE), getWorld().getRegistryManager());
    }
}
