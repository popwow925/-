package com.d0ck.aibuddy.task;

import com.d0ck.aibuddy.entity.AiBuddyEntity;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;

/** 采集方块：寻找 -> 走过去 -> 挡路就挖开 -> 挖掘 -> 捡掉落物 */
public class MineTask implements BuddyTask {
    private final String query;
    private final int wanted;
    private int mined = 0;
    private BlockPos target;
    private BlockPos lastBroken;
    private int collectTicks = 0;
    private int searchCooldown = 0;
    private int stuckTicks = 0;
    private int timeout = 20 * 300;
    private final Set<BlockPos> blacklist = new HashSet<>();
    private String result;

    public MineTask(String query, int count) {
        this.query = query;
        this.wanted = Math.max(1, Math.min(64, count));
    }

    @Override
    public boolean tick(AiBuddyEntity b) {
        World world = b.getWorld();
        if (--timeout <= 0) {
            result = "采集 " + query + " 超时，挖到了 " + mined + " 个";
            return true;
        }
        // 刚挖完：去捡掉落物
        if (collectTicks > 0) {
            collectTicks--;
            if (b.age % 10 == 0) b.getNavigation().startMovingTo(lastBroken.getX() + 0.5, lastBroken.getY(), lastBroken.getZ() + 0.5, 1.1);
            if (b.squaredDistanceTo(Vec3d.ofBottomCenter(lastBroken)) < 1.5) collectTicks = 0;
            return false;
        }
        if (mined >= wanted) {
            result = "采集完成：" + query + " ×" + mined;
            return true;
        }

        if (target == null || !isWanted(world.getBlockState(target))) {
            target = null;
            b.resetBreaking();
            if (searchCooldown-- > 0) return false;
            searchCooldown = 20;
            target = find(b);
            stuckTicks = 0;
            if (target == null) {
                result = mined > 0 ? "附近的 " + query + " 挖完了，共 " + mined + " 个" : "附近 20 格内找不到 " + query;
                return true;
            }
            BlockState st = world.getBlockState(target);
            if (st.isToolRequired() && !b.hasToolFor(st)) {
                result = "挖 " + query + " 需要合适的工具（比如镐子），我背包里没有";
                return true;
            }
        }

        double reach = 4.5;
        boolean inReach = b.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) <= reach * reach;
        if (inReach) {
            // 视线被挡住就先挖挡路的方块
            BlockPos obstacle = b.findObstacle(target);
            BlockPos dig = obstacle != null && b.canBreak(obstacle) ? obstacle : target;
            b.getNavigation().stop();
            if (b.mineStep(dig)) {
                if (dig.equals(target)) {
                    mined++;
                    lastBroken = target;
                    collectTicks = 40;
                    target = null;
                }
            }
            stuckTicks = 0;
            return false;
        }

        if (b.age % 10 == 0) {
            b.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1, 1.1);
        }
        if (b.getNavigation().isIdle()) stuckTicks += 1; else stuckTicks = Math.max(0, stuckTicks - 1);
        if (stuckTicks > 40) {
            // 走不过去：朝目标方向挖一条路
            BlockPos obstacle = b.findObstacle(target);
            if (obstacle != null && b.canBreak(obstacle)
                    && b.getEyePos().squaredDistanceTo(Vec3d.ofCenter(obstacle)) <= reach * reach) {
                b.mineStep(obstacle);
                return false;
            }
            if (stuckTicks > 120) {
                blacklist.add(target);
                target = null;
                b.resetBreaking();
            }
        }
        return false;
    }

    private boolean isWanted(BlockState st) {
        if (st.isAir()) return false;
        return BuddyTask.matches(Registries.BLOCK.getId(st.getBlock()).getPath(), query);
    }

    private BlockPos find(AiBuddyEntity b) {
        World world = b.getWorld();
        BlockPos center = b.getBlockPos();
        BlockPos hidden = null;
        for (BlockPos p : BlockPos.iterateOutwards(center, 20, 10, 20)) {
            if (blacklist.contains(p)) continue;
            BlockState st = world.getBlockState(p);
            if (!isWanted(st) || st.getHardness(world, p) < 0) continue;
            if (exposed(world, p)) return p.toImmutable();
            if (hidden == null) hidden = p.toImmutable();
        }
        return hidden; // 没有露天的就挖进去
    }

    private static boolean exposed(World world, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (!world.getBlockState(p.offset(d)).isOpaque()) return true;
        }
        return false;
    }

    @Override
    public String describe() {
        return "正在采集 " + query + "（" + mined + "/" + wanted + "）";
    }

    @Override
    public String result() {
        return result;
    }
}
