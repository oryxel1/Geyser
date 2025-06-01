/*
 * Copyright (c) 2025 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.session.cache;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.cloudburstmc.math.GenericMath;
import org.cloudburstmc.math.TrigMath;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;

@RequiredArgsConstructor
@Setter
public final class RewindCache {
    public static final long MAGIC_ELYTRA_BOOST_HACK_TIMESTAMP = -3234567890L;

    private final GeyserSession session;

    private Vector3f velocity = Vector3f.ZERO;
    private Vector3f cachedVelocity = null;

    @Getter
    private Vector3f position;

    @Getter
    private boolean horizontalCollision, onGround;

    @Getter
    private boolean rewinding, queueRewind;

    private boolean lastOneTick;
    private int elytraBoostCount;

    private int elytraBoostTick;

    public void tick(PlayerAuthInputPacket packet) {
        final SessionPlayerEntity entity = session.getPlayerEntity();

        if (this.elytraBoostCount > 0) {
            this.queueRewind = true;
        }

        if (this.lastOneTick) {
            this.queueRewind = true;
            this.lastOneTick = false;
        }

        if (this.queueRewind) {
            this.rewinding = true;
        }

        if (!this.rewinding) {
            this.position = entity.getPosition();
            this.velocity = packet.getDelta();
            return;
        }

        if (this.cachedVelocity != null && this.cachedVelocity.distanceSquared(packet.getDelta()) < this.velocity.distanceSquared(packet.getDelta())) {
            this.velocity = this.cachedVelocity;
            this.cachedVelocity = null;
        }

        final Vector3f view = MathUtils.getCameraOrientation(packet.getRotation().getX(), packet.getRotation().getY());
        Vector3f velocity = Vector3f.from(this.velocity);

        float v8 = entity.getPitch() * 0.017453292F;
        float v9 = (float) GenericMath.sqrt(view.getX() * view.getX() + view.getZ() * view.getZ());
        float v10 = view.lengthSquared();
        float v11 = (float) GenericMath.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        float v12 = TrigMath.cos(v8);
        float v14 = v12 * (Math.min((float) GenericMath.sqrt(v10) * 2.5F, 1.0F) * v12);

        float v15 = 1.0F / v9;

        velocity = velocity.sub(0,
            (v14 * 0.75F - 1.0F) * -(velocity.getY() < 0.0 && session.getEffectCache().getEntityEffects().contains(Effect.SLOW_FALLING) ?
                Math.min(0.08F, 0.01F) : 0.08F), 0);
        if (velocity.getY() < 0.0 && v9 > 0.0) {
            float v21 = velocity.getY() * -0.1F * v14;
            velocity = velocity.add(view.getX() * v21 * v15, v21, view.getZ() * v21 * v15);
        }
        if (v8 < 0.0) {
            float v26 = TrigMath.sin(v8) * v11 * -0.039999999F;
            velocity = velocity.add(-(v26 * view.getX() * v15), v26 * 3.2F, -(v26 * view.getZ() * v15));
        }
        if (v9 > 0.0) {
            velocity = velocity.add((v15 * view.getX() * v11 - velocity.getX()) * 0.1F, 0, (v15 * view.getZ() * v11 - velocity.getZ()) * 0.1F);
        }

        if (this.elytraBoostTick > 0) {
            velocity = velocity.add((view.getX() * 0.1F) + (((view.getX() * 1.5F) - velocity.getX()) * 0.5F),
                (view.getY() * 0.1F) + (((view.getY() * 1.5F) - velocity.getY()) * 0.5F),
                (view.getZ() * 0.1F) + (((view.getZ() * 1.5F) - velocity.getZ()) * 0.5F));
        }

        this.elytraBoostTick--;

        this.velocity = velocity.mul(0.99F, 0.98F, 0.99F);

        Vector3d vector3d = session.getCollisionManager().correctPlayerMovement(this.velocity.toDouble(), true, false);
        this.position = this.position.add(vector3d.toFloat());

        // Technically not 1.0E-5 but precision loss between float and double is a thing so.
        boolean bl = Math.abs(this.velocity.getX() - vector3d.getX()) > 1.0E-5F;
        boolean bl2 = Math.abs(this.velocity.getZ() - vector3d.getZ()) > 1.0E-5F;
        boolean bl3 = Math.abs(this.velocity.getY() - vector3d.getY()) > 1.0E-5F;

        this.onGround = bl3 && this.velocity.getY() < 0;

        boolean cantKeepRewinding = !entity.canStartGliding() || !entity.isGliding(); // Only support gliding for now.
        if (cantKeepRewinding) {
            // Forcefully stop rewind
            entity.moveAbsolute(entity.position(), entity.getYaw(), entity.getPitch(), entity.getHeadYaw(), entity.isOnGround(), true);
            System.out.println("Forcefully stop rewind!");

            session.setUnconfirmedTeleport(new TeleportCache(
                entity.getPosition().getX(),
                entity.position().getY(),
                entity.getPosition().getZ(), entity.getPitch(), entity.getYaw(),
                -1
            ));
        }

        this.horizontalCollision = bl || bl2;

        if (this.horizontalCollision) {
            this.velocity = Vector3f.from(bl ? 0 : this.velocity.getX(), this.velocity.getY(), bl2 ? 0 : this.velocity.getZ());
        }

        if (bl3) {
            this.velocity = Vector3f.from(this.velocity.getX(), 0, this.velocity.getZ());
        }

        if (cantKeepRewinding) {
            this.queueRewind = false;
            return;
        }

        if (this.queueRewind) {
            this.rewind();
            this.queueRewind = false;
        }

        double differ = this.velocity.distance(packet.getDelta());
        if (differ < 0.001) {
            this.rewinding = false;
            System.out.println("Stop rewinding!");
        } else if (differ > 0.15) {
            this.queueRewind = true;
        }
    }

    public void rewind() {
        final SessionPlayerEntity entity = session.getPlayerEntity();

        CorrectPlayerMovePredictionPacket packet = new CorrectPlayerMovePredictionPacket();
        packet.setPosition(entity.getPosition());
        packet.setDelta(this.velocity);
        packet.setTick(this.session.getClientTick());
        packet.setOnGround(entity.isOnGround());
        this.session.sendUpstreamPacketImmediately(packet);

        this.position = entity.getPosition();
    }

    public void queueElytraBoost() {
        this.elytraBoostCount++;

        NetworkStackLatencyPacket latencyPacket = new NetworkStackLatencyPacket();
        latencyPacket.setFromServer(true);
        latencyPacket.setTimestamp(MAGIC_ELYTRA_BOOST_HACK_TIMESTAMP);
        session.sendUpstreamPacketImmediately(latencyPacket);
    }

    public void pollElytraBoost() {
        if (this.elytraBoostCount > 0) {
            this.elytraBoostCount--;
        }

        if (this.elytraBoostCount == 0) {
            this.lastOneTick = true;
        }
    }
}
