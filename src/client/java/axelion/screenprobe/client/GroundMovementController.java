package axelion.screenprobe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

final class GroundMovementController {
    private GroundMovementController() {
    }

    static boolean walkToward(Minecraft client, Vec3 target, double stopDistance, double sprintDistance,
                              boolean jumpForHigherTarget, boolean jumpOnCollision, float maxYawStep) {
        if (client == null || client.options == null || client.player == null || target == null) {
            release(client);
            return false;
        }

        LocalPlayer player = client.player;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance > 1.0E-4D) {
            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float yaw = approachAngle(player.getYRot(), targetYaw, maxYawStep);
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setYBodyRot(yaw);
        }

        boolean walking = horizontalDistance > stopDistance;
        client.options.keyUp.setDown(walking);
        client.options.keySprint.setDown(walking && sprintDistance > 0.0D && horizontalDistance > sprintDistance);
        client.options.keyJump.setDown(walking && ((jumpForHigherTarget && target.y > player.getY() + 0.35D)
                || (jumpOnCollision && (player.horizontalCollision || player.onGround() && target.y > player.getY() + 0.15D))));
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        return walking;
    }

    static void release(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
    }

    static void steerForward(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
        client.options.keyJump.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
    }

    static void steerToward(Minecraft client, Vec3 target, boolean letExternalAim, float maxYawStep, float maxPitchStep) {
        if (client == null || client.player == null || target == null) {
            release(client);
            return;
        }

        if (!letExternalAim) {
            smoothFacePoint(client, target, maxYawStep, maxPitchStep);
        }
        steerForward(client);
    }

    static void faceEntity(Minecraft client, Entity entity, double yOffset) {
        if (entity == null) {
            return;
        }
        facePoint(client, entity.position().add(0.0D, yOffset + entity.getBbHeight() * 0.5D, 0.0D));
    }

    static void smoothFaceEntity(Minecraft client, Entity entity, double yOffset, float maxYawStep, float maxPitchStep) {
        if (entity == null) {
            return;
        }
        smoothFacePoint(client, entity.position().add(0.0D, yOffset + entity.getBbHeight() * 0.5D, 0.0D), maxYawStep, maxPitchStep);
    }

    static void faceBlockCenter(Minecraft client, BlockPos targetPos) {
        if (targetPos == null) {
            return;
        }
        facePoint(client, targetPos.getCenter());
    }

    static void smoothFacePoint(Minecraft client, Vec3 target, float maxYawStep, float maxPitchStep) {
        LocalPlayer player = client.player;
        Vec3 eyePos = player.getEyePosition();
        LookAngles angles = lookAngles(eyePos, target);
        smoothLook(client, angles.yaw(), angles.pitch(), maxYawStep, maxPitchStep);
    }

    static void smoothLook(Minecraft client, float targetYaw, float targetPitch, float maxYawStep, float maxPitchStep) {
        LocalPlayer player = client.player;
        float yaw = approachAngle(player.getYRot(), targetYaw, maxYawStep);
        float pitch = approachAngle(player.getXRot(), clampFloat(targetPitch, -70.0F, 60.0F), maxPitchStep);
        applyLook(player, yaw, pitch);
    }

    static void facePoint(Minecraft client, Vec3 target) {
        LocalPlayer player = client.player;
        LookAngles angles = lookAngles(player.getEyePosition(), target);
        applyLook(player, angles.yaw(), angles.pitch());
    }

    static int distanceToGround(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return 65;
        }

        BlockPos playerPos = client.player.blockPosition();
        for (int distance = 0; distance <= 64; distance++) {
            BlockPos pos = playerPos.below(distance + 1);
            if (client.level.getBlockState(pos).blocksMotion() || !client.level.getFluidState(pos).isEmpty()) {
                return distance;
            }
        }
        return 65;
    }

    static float yawTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    static float approachAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        } else if (delta < -maxStep) {
            delta = -maxStep;
        }
        return current + delta;
    }

    static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static LookAngles lookAngles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontalDistance, 1.0E-4D)));
        return new LookAngles(yaw, pitch);
    }

    private static void applyLook(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(pitch);
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    private record LookAngles(float yaw, float pitch) {
    }
}
