package com.d0ck.aibuddy.task;

import com.d0ck.aibuddy.entity.AiBuddyEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按坐标列表逐块放置 */
public class BuildTask implements BuddyTask {
    private final String label;
    private final String blockQuery; // null = 用背包里最多的方块
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final Map<BlockPos, Integer> retries = new HashMap<>();
    private final int total;
    private int placed = 0;
    private int cooldown = 0;
    private int stuck = 0;
    private String result;

    public BuildTask(String label, String blockQuery, List<BlockPos> positions) {
        this.label = label;
        this.blockQuery = blockQuery == null || blockQuery.isBlank() ? null : blockQuery;
        List<BlockPos> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        queue.addAll(sorted);
        total = sorted.size();
    }

    /** 根据形状生成坐标，以主人脚下为原点、朝主人面朝方向向前 2 格开始 */
    public static List<BlockPos> shape(String shape, PlayerEntity owner, int w, int h, int d) {
        w = clamp(w, 1, 15);
        h = clamp(h, 1, 10);
        d = clamp(d, 1, 15);
        Direction fwd = owner.getHorizontalFacing();
        Direction right = fwd.rotateYClockwise();
        BlockPos origin = owner.getBlockPos().offset(fwd, 2);
        List<BlockPos> out = new ArrayList<>();
        int half = w / 2;
        switch (shape) {
            case "pillar" -> {
                for (int y = 0; y < h; y++) out.add(origin.up(y));
            }
            case "wall" -> {
                for (int y = 0; y < h; y++)
                    for (int x = -half; x < w - half; x++)
                        out.add(origin.offset(right, x).up(y));
            }
            case "floor" -> {
                for (int z = 0; z < d; z++)
                    for (int x = -half; x < w - half; x++)
                        out.add(origin.offset(right, x).offset(fwd, z).down());
            }
            default -> { // house
                w = Math.max(3, w);
                d = Math.max(3, d);
                h = Math.max(3, h);
                half = w / 2;
                for (int y = 0; y <= h; y++) {
                    for (int z = 0; z < d; z++) {
                        for (int x = -half; x < w - half; x++) {
                            boolean edge = x == -half || x == w - half - 1 || z == 0 || z == d - 1;
                            boolean roof = y == h;
                            boolean door = z == 0 && x == 0 && y < 2;
                            if ((edge && !door) || roof) out.add(origin.offset(right, x).offset(fwd, z).up(y));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public boolean tick(AiBuddyEntity b) {
        World world = b.getWorld();
        while (!queue.isEmpty() && !world.getBlockState(queue.peekFirst()).isReplaceable()) queue.pollFirst();
        if (queue.isEmpty()) {
            result = label + "完成，放了 " + placed + " 块";
            return true;
        }
        if (cooldown-- > 0) return false;

        BlockPos pos = queue.peekFirst();
        if (!b.hasBuildingBlock(blockQuery, pos)) {
            result = label + "材料不够，已放 " + placed + " 块，还差 " + queue.size() + " 块"
                    + (blockQuery != null ? "（需要 " + blockQuery + "）" : "");
            return true;
        }

        Box blockBox = new Box(pos);
        PlayerEntity owner = b.getOwnerPlayer();
        boolean blockedByEntity = b.getBoundingBox().intersects(blockBox)
                || (owner != null && owner.getBoundingBox().intersects(blockBox));

        double distSq = b.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos));
        if (distSq > 4.5 * 4.5 || blockedByEntity) {
            if (blockedByEntity || stuck > 100) {
                requeue(pos);
                stuck = 0;
                if (blockedByEntity) b.stepAwayFrom(pos);
                return false;
            }
            if (b.age % 10 == 0) b.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 2, 1.1);
            stuck++;
            return false;
        }
        stuck = 0;
        b.getNavigation().stop();
        if (b.placeBuildingBlock(blockQuery, pos)) {
            placed++;
            queue.pollFirst();
            cooldown = 3;
        } else {
            requeue(pos);
        }
        return false;
    }

    private void requeue(BlockPos pos) {
        queue.pollFirst();
        int r = retries.merge(pos, 1, Integer::sum);
        if (r <= 3) queue.addLast(pos);
    }

    @Override
    public String describe() {
        return "正在" + label + "（" + placed + "/" + total + "）";
    }

    @Override
    public String result() {
        return result;
    }
}
