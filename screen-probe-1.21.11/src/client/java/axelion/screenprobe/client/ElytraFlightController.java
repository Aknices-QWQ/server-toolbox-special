package axelion.screenprobe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

final class ElytraFlightController {
    static final double DEFAULT_GRAVITY = -0.08D;
    private static final double VERTICAL_LOOK_EPSILON = 1.0E-4D;

    private ElytraFlightController() {
    }

    static void steerToward(Minecraft client, Vec3 target, boolean letExternalAim, boolean travelMode, Profile profile) {
        if (client == null || client.player == null || target == null) {
            GroundMovementController.release(client);
            return;
        }

        LocalPlayer player = client.player;
        if (!letExternalAim) {
            Vec3 eyePos = player.getEyePosition();
            double dx = target.x - eyePos.x;
            double dz = target.z - eyePos.z;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = GroundMovementController.yawTo(eyePos, target);
            float targetPitch = computePitch(client, target, targetYaw, horizontalDistance, travelMode, profile);
            GroundMovementController.smoothLook(client, targetYaw, targetPitch, profile.maxYawStep(), profile.maxPitchStep());
        }
        GroundMovementController.steerForward(client);
    }

    static float computePitch(Minecraft client, Vec3 target, float targetYaw, double horizontalDistance,
                              boolean travelMode, Profile profile) {
        LocalPlayer player = client.player;
        double altitudeError = target.y - player.getEyeY();
        double verticalSpeed = player.getDeltaMovement().y;
        double horizontalSpeed = Math.sqrt(player.getDeltaMovement().horizontalDistanceSqr());
        int groundDistance = GroundMovementController.distanceToGround(client);
        double predictedGroundDistance = groundDistance + Math.min(0.0D, verticalSpeed) * 12.0D;

        float desiredPitch;
        if (groundDistance <= profile.criticalAltitudeFireworkBlocks() + 2
                || predictedGroundDistance <= profile.lowAltitudeFireworkBlocks()) {
            desiredPitch = -18.0F;
        } else if (altitudeError > 24.0D || (altitudeError > 10.0D && verticalSpeed < -0.06D)) {
            desiredPitch = -16.0F;
        } else if (verticalSpeed < -0.36D && groundDistance < 24) {
            desiredPitch = -15.0F;
        } else if (horizontalSpeed < 0.55D && horizontalDistance > 36.0D) {
            desiredPitch = altitudeError > 4.0D ? -10.0F : 5.0F;
        } else if (horizontalDistance > 72.0D && altitudeError < -8.0D && groundDistance > 28) {
            desiredPitch = travelMode ? 10.0F : 7.0F;
        } else if (horizontalDistance < 16.0D) {
            desiredPitch = GroundMovementController.clampFloat((float) (-altitudeError * 0.35D), -12.0F, 10.0F);
        } else {
            double pitch = -altitudeError * 0.18D - verticalSpeed * 10.0D;
            if (travelMode && groundDistance > 24 && altitudeError < 4.0D) {
                pitch += 2.0D;
            }
            desiredPitch = GroundMovementController.clampFloat((float) pitch, -16.0F, 12.0F);
        }

        return refinePitchWithGlideFormula(player, target, targetYaw, desiredPitch, travelMode, profile, groundDistance);
    }

    static boolean shouldUseTravelFirework(Minecraft client, Vec3 target, double farDistance, Profile profile) {
        LocalPlayer player = client.player;
        int groundDistance = GroundMovementController.distanceToGround(client);
        double verticalSpeed = player.getDeltaMovement().y;
        double predictedGroundDistance = groundDistance + Math.min(0.0D, verticalSpeed) * 10.0D;
        double distance = player.position().distanceTo(target);
        double speedSqr = player.getDeltaMovement().lengthSqr();
        if (groundDistance <= profile.criticalAltitudeFireworkBlocks() - 1
                || predictedGroundDistance <= profile.criticalAltitudeFireworkBlocks()
                || (groundDistance <= profile.lowAltitudeFireworkBlocks() && verticalSpeed < -0.20D)) {
            return true;
        }
        return distance > farDistance + 32.0D && speedSqr < 0.18D && player.getY() < target.y + 4.0D;
    }

    static Vec3 predictGlideVelocity(Vec3 currentVelocity, float pitchDegrees, float yawDegrees) {
        return predictGlideVelocity(currentVelocity, pitchDegrees, yawDegrees, DEFAULT_GRAVITY);
    }

    static Vec3 predictGlideVelocity(Vec3 currentVelocity, float pitchDegrees, float yawDegrees, double gravity) {
        double pitch = Math.toRadians(pitchDegrees);
        double yaw = Math.toRadians(yawDegrees);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);
        double cosPitchSquared = cosPitch * cosPitch;
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double originalHorizontalSpeed = horizontalSpeed(currentVelocity);

        Vec3 velocity = new Vec3(
                currentVelocity.x,
                currentVelocity.y + gravity * (1.0D - 0.75D * cosPitchSquared),
                currentVelocity.z
        );

        if (Math.abs(cosPitch) <= VERTICAL_LOOK_EPSILON) {
            return velocity;
        }

        if (velocity.y < 0.0D) {
            velocity = new Vec3(
                    velocity.x + 0.1D * velocity.y * cosPitchSquared * sinYaw,
                    (1.0D - 0.1D * cosPitchSquared) * velocity.y,
                    velocity.z - 0.1D * velocity.y * cosPitchSquared * cosYaw
            );
        }

        if (pitchDegrees < 0.0F) {
            velocity = new Vec3(
                    velocity.x - 0.04D * originalHorizontalSpeed * sinPitch * sinYaw,
                    velocity.y - 0.128D * originalHorizontalSpeed * sinPitch,
                    velocity.z + 0.04D * originalHorizontalSpeed * sinPitch * cosYaw
            );
        }

        velocity = new Vec3(
                0.9D * velocity.x - 0.1D * originalHorizontalSpeed * sinYaw,
                velocity.y,
                0.9D * velocity.z + 0.1D * originalHorizontalSpeed * cosYaw
        );
        return velocity.multiply(0.99D, 0.98D, 0.99D);
    }

    static Vec3 predictFireworkVelocity(Vec3 currentVelocity, float pitchDegrees, float yawDegrees) {
        Vec3 look = lookVector(pitchDegrees, yawDegrees);
        return look.scale(0.85D).add(currentVelocity.scale(0.5D));
    }

    static Vec3 predictRiptideVelocity(Vec3 currentVelocity, float pitchDegrees, float yawDegrees, int riptideLevel) {
        Vec3 look = lookVector(pitchDegrees, yawDegrees);
        return currentVelocity.add(look.scale(0.75D * (riptideLevel + 1)));
    }

    static double kineticDamage(double previousHorizontalSpeed, double postCollisionHorizontalSpeed) {
        double collisionSpeed = Math.max(0.0D, previousHorizontalSpeed - postCollisionHorizontalSpeed);
        return Math.max(0.0D, 10.0D * collisionSpeed - 3.0D);
    }

    private static double horizontalSpeed(Vec3 velocity) {
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private static float refinePitchWithGlideFormula(LocalPlayer player, Vec3 target, float targetYaw, float desiredPitch,
                                                     boolean travelMode, Profile profile, int groundDistance) {
        Vec3 currentVelocity = player.getDeltaMovement();
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontalDistance = Math.max(1.0E-4D, Math.sqrt(dx * dx + dz * dz));
        double altitudeError = target.y - player.getEyeY();
        double desiredVerticalSpeed = GroundMovementController.clampFloat(
                (float) (altitudeError * 0.025D),
                travelMode ? -0.22F : -0.18F,
                altitudeError > 0.0D ? 0.12F : 0.06F
        );
        if (groundDistance <= profile.lowAltitudeFireworkBlocks() + 4) {
            desiredVerticalSpeed = Math.max(desiredVerticalSpeed, 0.02D);
        }

        float[] candidates = new float[] {
                desiredPitch,
                desiredPitch - 6.0F,
                desiredPitch - 3.0F,
                desiredPitch + 3.0F,
                desiredPitch + 6.0F
        };
        float bestPitch = desiredPitch;
        double bestScore = Double.MAX_VALUE;
        for (float candidate : candidates) {
            float pitch = GroundMovementController.clampFloat(candidate, -18.0F, 12.0F);
            Vec3 nextVelocity = predictGlideVelocity(currentVelocity, pitch, targetYaw);
            double forwardSpeed = (nextVelocity.x * dx + nextVelocity.z * dz) / horizontalDistance;
            double nextGroundDistance = groundDistance + Math.min(0.0D, nextVelocity.y) * 12.0D;
            double score = Math.abs(nextVelocity.y - desiredVerticalSpeed) * 2.4D
                    - forwardSpeed * 0.08D
                    + Math.abs(pitch - desiredPitch) * 0.01D;
            if (nextGroundDistance <= profile.criticalAltitudeFireworkBlocks()) {
                score += 3.0D;
            }
            if (score < bestScore) {
                bestScore = score;
                bestPitch = pitch;
            }
        }
        return bestPitch;
    }

    private static Vec3 lookVector(float pitchDegrees, float yawDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double yaw = Math.toRadians(yawDegrees);
        double cosPitch = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    record Profile(int lowAltitudeFireworkBlocks, int criticalAltitudeFireworkBlocks,
                   float maxYawStep, float maxPitchStep) {
    }
}
