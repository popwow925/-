package com.d0ck.aibuddy.task;

import com.d0ck.aibuddy.entity.AiBuddyEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

public final class SimpleTasks {
    private SimpleTasks() {}

    /** 走到坐标 */
    public static class Goto implements BuddyTask {
        private final BlockPos pos;
        private int ticks = 0;
        private String result;

        public Goto(BlockPos pos) { this.pos = pos; }

        @Override
        public boolean tick(AiBuddyEntity b) {
            ticks++;
            if (b.squaredDistanceTo(Vec3d.ofBottomCenter(pos)) < 4) {
                result = "到达 " + pos.toShortString();
                return true;
            }
            if (ticks % 20 == 1) b.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1, 1.2);
            if (ticks > 20 * 60) {
                result = "去 " + pos.toShortString() + " 的路走不通";
                return true;
            }
            return false;
        }

        @Override public String describe() { return "正在前往 " + pos.toShortString(); }
        @Override public String result() { return result; }
    }

    /** 攻击某种生物 */
    public static class Attack implements BuddyTask {
        private final String query;
        private LivingEntity target;
        private int kills = 0;
        private int ticks = 0;
        private String result;

        public Attack(String query) { this.query = query; }

        @Override
        public boolean tick(AiBuddyEntity b) {
            ticks++;
            if (target != null && !target.isAlive()) {
                kills++;
                target = null;
                result = "干掉了 " + query;
                return true;
            }
            if (target == null || target.isRemoved() || b.squaredDistanceTo(target) > 32 * 32) {
                List<LivingEntity> list = b.getWorld().getEntitiesByClass(LivingEntity.class,
                        b.getBoundingBox().expand(24),
                        e -> e != b && e.isAlive() && !(e instanceof PlayerEntity) && !(e instanceof AiBuddyEntity)
                                && BuddyTask.matches(Registries.ENTITY_TYPE.getId(e.getType()).getPath(), query));
                target = list.stream().min(Comparator.comparingDouble(b::squaredDistanceTo)).orElse(null);
                if (target == null) {
                    result = "附近没找到 " + query;
                    return true;
                }
                b.equipBestWeapon();
            }
            b.setTarget(target);
            if (ticks > 20 * 60) {
                result = "追不上 " + query;
                b.setTarget(null);
                return true;
            }
            return false;
        }

        @Override public boolean pausesInCombat() { return false; }
        @Override public String describe() { return "正在攻击 " + query; }
        @Override public String result() { return result; }
    }

    /** 走到主人身边把东西给他 */
    public static class Give implements BuddyTask {
        private final String query;
        private final int count;
        private int ticks = 0;
        private String result;

        public Give(String query, int count) {
            this.query = query;
            this.count = count <= 0 ? Integer.MAX_VALUE : count;
        }

        @Override
        public boolean tick(AiBuddyEntity b) {
            PlayerEntity owner = b.getOwnerPlayer();
            if (owner == null) {
                result = "主人不在线";
                return true;
            }
            ticks++;
            if (b.squaredDistanceTo(owner) > 3 * 3) {
                if (ticks % 10 == 1) b.getNavigation().startMovingTo(owner, 1.2);
                if (ticks > 20 * 20) b.teleportNear(owner);
                return false;
            }
            b.getNavigation().stop();
            b.getLookControl().lookAt(owner, 30, 30);
            int given = b.giveToOwner(query, count);
            result = given > 0 ? "给了主人 " + given + " 个 " + query : "我背包里没有 " + query;
            return true;
        }

        @Override public String describe() { return "正在把 " + query + " 交给主人"; }
        @Override public String result() { return result; }
    }

    /** 捡附近的掉落物 */
    public static class Collect implements BuddyTask {
        private int ticks = 0;

        @Override
        public boolean tick(AiBuddyEntity b) {
            if (++ticks > 20 * 20) return true;
            if (ticks % 10 != 1) return false;
            List<ItemEntity> items = b.getWorld().getEntitiesByClass(ItemEntity.class, b.getBoundingBox().expand(12), ItemEntity::isAlive);
            ItemEntity nearest = items.stream().min(Comparator.comparingDouble(b::squaredDistanceTo)).orElse(null);
            if (nearest == null) return true;
            b.getNavigation().startMovingTo(nearest, 1.2);
            return false;
        }

        @Override public String describe() { return "正在捡附近的掉落物"; }
    }
}
