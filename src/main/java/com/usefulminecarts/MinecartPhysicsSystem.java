package com.usefulminecarts;

import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.minecart.MinecartComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.RailConfig;
import com.hypixel.hytale.protocol.RailPoint;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Physics system for unmounted minecarts.
 * For slopes, computes snap position based on detected downhill direction.
 * For flat rails, uses rotation-transformed rail points.
 */
public class MinecartPhysicsSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Edge constants for cleaner direction handling
    // Standard convention: NORTH = -Z, SOUTH = +Z, WEST = -X, EAST = +X
    private static final int EDGE_WEST = 0;   // -X direction
    private static final int EDGE_EAST = 1;   // +X direction
    private static final int EDGE_SOUTH = 2;  // +Z direction (towards positive Z)
    private static final int EDGE_NORTH = 3;  // -Z direction (towards negative Z)

    // Stores signed velocity (positive = rail's positive direction, negative = backward)
    private static final Map<Integer, Double> minecartVelocities = new ConcurrentHashMap<>();
    // Stores the facing yaw for tracking (used for cleanup)
    private static final Map<Integer, Float> minecartFacingYaw = new ConcurrentHashMap<>();
    // Stores the world movement direction (actual direction cart is traveling, persisted across ticks)
    private static final Map<Integer, double[]> minecartWorldDirection = new ConcurrentHashMap<>();

    private int tickCount = 0;

    // Helper: Get entry edge from world movement direction
    // Entry edge = the edge of the block the cart enters THROUGH (where it came from)
    private int getEntryEdge(double worldMoveX, double worldMoveZ) {
        if (Math.abs(worldMoveX) > Math.abs(worldMoveZ)) {
            return worldMoveX > 0 ? EDGE_WEST : EDGE_EAST;  // Moving +X means entering from west (-X side)
        } else {
            // NORTH = -Z, SOUTH = +Z
            // Moving +Z (south) means entering through NORTH edge (-Z side of block)
            // Moving -Z (north) means entering through SOUTH edge (+Z side of block)
            return worldMoveZ > 0 ? EDGE_NORTH : EDGE_SOUTH;
        }
    }

    // Helper: Get opposite edge
    private int getOppositeEdge(int edge) {
        switch (edge) {
            case EDGE_WEST: return EDGE_EAST;
            case EDGE_EAST: return EDGE_WEST;
            case EDGE_SOUTH: return EDGE_NORTH;
            case EDGE_NORTH: return EDGE_SOUTH;
            default: return edge;
        }
    }

    // Helper: Get world direction for an edge (direction TO exit through that edge)
    // NORTH = -Z, SOUTH = +Z
    private double[] getEdgeDirection(int edge) {
        switch (edge) {
            case EDGE_WEST: return new double[]{-1, 0};   // Exit west = move -X
            case EDGE_EAST: return new double[]{1, 0};    // Exit east = move +X
            case EDGE_SOUTH: return new double[]{0, 1};   // Exit south = move +Z
            case EDGE_NORTH: return new double[]{0, -1};  // Exit north = move -Z
            default: return new double[]{0, 0};
        }
    }

    // Helper: Turn right (clockwise)
    private int turnRight(int edge) {
        switch (edge) {
            case EDGE_NORTH: return EDGE_EAST;
            case EDGE_EAST: return EDGE_SOUTH;
            case EDGE_SOUTH: return EDGE_WEST;
            case EDGE_WEST: return EDGE_NORTH;
            default: return edge;
        }
    }

    // Helper: Turn left (counter-clockwise)
    private int turnLeft(int edge) {
        switch (edge) {
            case EDGE_NORTH: return EDGE_WEST;
            case EDGE_WEST: return EDGE_SOUTH;
            case EDGE_SOUTH: return EDGE_EAST;
            case EDGE_EAST: return EDGE_NORTH;
            default: return edge;
        }
    }

    // Helper: Check if there's a rail in the direction of an edge from a snap position
    // NORTH = -Z, SOUTH = +Z
    private boolean hasRailInDirection(World world, RailSnap snap, int edge) {
        int dx = 0, dz = 0;
        switch (edge) {
            case EDGE_WEST: dx = -1; break;
            case EDGE_EAST: dx = 1; break;
            case EDGE_SOUTH: dz = 1; break;   // South = +Z
            case EDGE_NORTH: dz = -1; break;  // North = -Z
        }
        return hasRailAt(world, snap.blockX + dx, snap.blockY, snap.blockZ + dz)
            || hasRailAt(world, snap.blockX + dx, snap.blockY - 1, snap.blockZ + dz)
            || hasRailAt(world, snap.blockX + dx, snap.blockY + 1, snap.blockZ + dz);
    }

    // Helper: Get edge name for logging
    private String getEdgeName(int edge) {
        switch (edge) {
            case EDGE_WEST: return "WEST";
            case EDGE_EAST: return "EAST";
            case EDGE_SOUTH: return "SOUTH";
            case EDGE_NORTH: return "NORTH";
            default: return "UNKNOWN";
        }
    }

    // Helper: Determine which edge a rail point is at based on block-local coordinates (0-1 range)
    // NORTH = -Z, SOUTH = +Z
    // x ≈ 0 → WEST (-X edge), x ≈ 1 → EAST (+X edge)
    // z ≈ 0 → NORTH (-Z edge), z ≈ 1 → SOUTH (+Z edge)
    private int getEdgeFromPoint(float x, float z) {
        // Check which coordinate is closest to an edge (0 or 1)
        float distWest = x;           // distance to x=0 (WEST edge, -X side)
        float distEast = 1.0f - x;    // distance to x=1 (EAST edge, +X side)
        float distNorth = z;          // distance to z=0 (NORTH edge, -Z side)
        float distSouth = 1.0f - z;   // distance to z=1 (SOUTH edge, +Z side)

        // Find the minimum distance to determine which edge
        float minDist = Math.min(Math.min(distWest, distEast), Math.min(distNorth, distSouth));

        if (minDist == distWest) return EDGE_WEST;
        if (minDist == distEast) return EDGE_EAST;
        if (minDist == distNorth) return EDGE_NORTH;
        return EDGE_SOUTH;
    }

    @Nonnull
    private final Query<EntityStore> query;

    public MinecartPhysicsSystem() {
        this.query = Query.and(MinecartComponent.getComponentType());
        LOGGER.atInfo().log("[MinecartPhysics] Physics system initialized!");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        tickCount++;
        Ref<EntityStore> minecartRef = archetypeChunk.getReferenceTo(index);
        boolean shouldLog = (tickCount % 4 == 0);

        MountedByComponent mountedBy = store.getComponent(minecartRef, MountedByComponent.getComponentType());
        if (mountedBy != null && !mountedBy.getPassengers().isEmpty()) {
            return;
        }

        NetworkId networkId = store.getComponent(minecartRef, NetworkId.getComponentType());
        if (networkId == null) return;
        int entityId = networkId.getId();

        TransformComponent transform = commandBuffer.getComponent(minecartRef, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d position = transform.getPosition();
        Vector3f rotation = transform.getRotation();

        World world = store.getExternalData().getWorld();
        if (world == null) return;

        // Use persisted direction for initial snap to avoid T-junction perpendicular segment issues
        // Without this, the initial snap could pull the cart to a perpendicular segment and reset position
        double[] persistedDirForSnap = minecartWorldDirection.get(entityId);
        float snapDirX = 0, snapDirZ = 0;
        if (persistedDirForSnap != null) {
            snapDirX = (float) persistedDirForSnap[0];
            snapDirZ = (float) persistedDirForSnap[1];
        }
        RailSnap snap = findBestRailSnapWithDirection(world, position, snapDirX, snapDirZ);
        if (snap == null) {
            minecartVelocities.remove(entityId);
            minecartFacingYaw.remove(entityId);
            minecartWorldDirection.remove(entityId);
            return;
        }

        // Get signed velocity (positive = rail's positive direction, negative = backward)
        double velocity = minecartVelocities.getOrDefault(entityId, 0.0);
        float facingYaw = minecartFacingYaw.getOrDefault(entityId, rotation.getYaw());

        // On slope with no velocity - start moving downhill
        if (Math.abs(velocity) < MinecartConfig.getMinSpeed() && snap.isSlope) {
            // Downhill direction is the rail's positive direction (dirY < 0 means going down)
            velocity = MinecartConfig.getInitialPush();
        }

        // If stationary on flat rail, stay put
        if (Math.abs(velocity) < MinecartConfig.getMinSpeed() && !snap.isSlope) {
            transform.getPosition().x = snap.x;
            transform.getPosition().y = snap.y;
            transform.getPosition().z = snap.z;
            return;
        }

        // Apply gravity on slopes
        // Must determine uphill vs downhill from world direction, not velocity sign
        // (velocity is always positive magnitude, direction is tracked separately)
        if (snap.isSlope) {
            double gravityAccel = MinecartConfig.getAcceleration() * Math.abs(snap.dirY) * dt;

            // Determine if cart is going uphill or downhill
            // The slope's "positive" direction has dirY < 0 (going down)
            // horizDot > 0: cart moving in slope's positive direction = downhill
            // horizDot < 0: cart moving opposite = uphill
            double horizDot = 0;
            if (persistedDirForSnap != null) {
                horizDot = persistedDirForSnap[0] * snap.dirX + persistedDirForSnap[1] * snap.dirZ;
            }

            boolean goingDownhill = horizDot >= 0;

            if (goingDownhill) {
                // Moving downhill - gravity accelerates
                velocity += gravityAccel * MinecartConfig.getSlopeBoost();
            } else {
                // Moving uphill - gravity decelerates
                velocity -= gravityAccel * MinecartConfig.getUphillDrag();

                // If velocity goes negative, cart has stopped and should reverse (roll backward)
                if (velocity < 0) {
                    velocity = Math.abs(velocity);
                    // Reverse the persisted direction
                    if (persistedDirForSnap != null) {
                        persistedDirForSnap[0] = -persistedDirForSnap[0];
                        persistedDirForSnap[1] = -persistedDirForSnap[1];
                        minecartWorldDirection.put(entityId, persistedDirForSnap);
                    }
                }
            }
        }

        // Apply friction (reduces magnitude regardless of sign)
        velocity *= MinecartConfig.getFriction();

        // Apply accelerator rail boost (multiplier)
        if (snap.isAccelerator) {
            velocity *= MinecartConfig.getAcceleratorBoost();
            // Give stationary carts a push on accelerator rails
            if (Math.abs(velocity) < MinecartConfig.getMinSpeed()) {
                velocity = MinecartConfig.getInitialPush() * 2.0;
            }
        }

        // Clamp to max speed
        if (velocity > MinecartConfig.getMaxSpeed()) {
            velocity = MinecartConfig.getMaxSpeed();
        } else if (velocity < -MinecartConfig.getMaxSpeed()) {
            velocity = -MinecartConfig.getMaxSpeed();
        }

        // Stop if very slow on flat rail
        if (Math.abs(velocity) < MinecartConfig.getMinSpeed() && !snap.isSlope) {
            velocity = 0;
        }

        minecartVelocities.put(entityId, velocity);

        // Movement distance (can be negative for backward motion)
        double totalMoveDistance = velocity * dt;

        // Limit movement per step to prevent skipping rails at high speeds
        // Max 0.4 blocks per step ensures we don't skip corner entry points
        double maxStepSize = 0.4;
        int numSteps = Math.max(1, (int) Math.ceil(Math.abs(totalMoveDistance) / maxStepSize));
        double stepDistance = totalMoveDistance / numSteps;

        double newX = snap.x;
        double newY = snap.y;
        double newZ = snap.z;
        RailSnap currentSnap = snap;
        RailSnap newSnap = null;

        // Track world-space movement direction (actual direction cart is moving)
        // This MUST be persisted across ticks to handle rails with opposite segment directions
        double[] persistedDir = minecartWorldDirection.get(entityId);
        double worldMoveX, worldMoveZ;

        if (persistedDir != null) {
            // Use persisted direction from previous tick
            worldMoveX = persistedDir[0];
            worldMoveZ = persistedDir[1];
            // Round to cardinal direction to avoid drift
            if (Math.abs(worldMoveX) > Math.abs(worldMoveZ)) {
                worldMoveX = Math.signum(worldMoveX);
                worldMoveZ = 0;
            } else if (Math.abs(worldMoveZ) > 0.01) {
                worldMoveX = 0;
                worldMoveZ = Math.signum(worldMoveZ);
            }
        } else {
            // First tick or just got on rail - compute from current state
            // Use the rail direction and velocity to determine initial world direction
            double moveX = snap.dirX * Math.signum(velocity);
            double moveZ = snap.dirZ * Math.signum(velocity);
            // Round to cardinal direction
            if (Math.abs(moveX) > Math.abs(moveZ)) {
                worldMoveX = Math.signum(moveX);
                worldMoveZ = 0;
            } else {
                worldMoveX = 0;
                worldMoveZ = Math.signum(moveZ);
            }
            // Handle slopes going +Z
            if (snap.isSlope && snap.dirZ > 0) {
                worldMoveX = 0;
                worldMoveZ = 1;
            } else if (snap.isSlope && snap.dirZ < 0) {
                worldMoveX = 0;
                worldMoveZ = -1;
            } else if (snap.isSlope && snap.dirX > 0) {
                worldMoveX = 1;
                worldMoveZ = 0;
            } else if (snap.isSlope && snap.dirX < 0) {
                worldMoveX = -1;
                worldMoveZ = 0;
            }
            // Initialize persisted direction
            minecartWorldDirection.put(entityId, new double[]{worldMoveX, worldMoveZ});
        }

        // Ensure velocity is positive (magnitude only) - direction is tracked by worldMove
        // This prevents oscillation issues when crossing rails with different segment orientations
        if (velocity < 0) {
            velocity = Math.abs(velocity);
            minecartVelocities.put(entityId, velocity);
        }

        // Sub-step movement to follow rails properly at high speeds
        for (int step = 0; step < numSteps; step++) {
            // Move one step using WORLD direction (not rail segment direction)
            // This prevents oscillation when crossing rails with opposite segment orientations
            // For slopes, we still need the Y component from the rail
            double stepMoveX = worldMoveX * Math.abs(stepDistance);
            double stepMoveZ = worldMoveZ * Math.abs(stepDistance);
            // Y movement comes from the rail's slope (if any)
            double stepMoveY = currentSnap.dirY * Math.abs(stepDistance);
            // If moving against the slope direction, negate Y
            double horizDot = worldMoveX * currentSnap.dirX + worldMoveZ * currentSnap.dirZ;
            if (horizDot < 0 && currentSnap.isSlope) {
                stepMoveY = -stepMoveY;
            }

            // Use current tracked position (newX/Y/Z) for step calculation, not snap position
            // This allows proper progression when going straight through junctions
            double stepX = newX + stepMoveX;
            double stepY = newY + stepMoveY;
            double stepZ = newZ + stepMoveZ;

            // Use world movement direction for rail search
            float moveDirX = (float) worldMoveX;
            float moveDirZ = (float) worldMoveZ;

            // Find rail at new position
            Vector3d stepPos = new Vector3d(stepX, stepY, stepZ);
            newSnap = findBestRailSnapWithDirection(world, stepPos, moveDirX, moveDirZ);

            if (newSnap == null) {
                // No rail found - stop at current position
                velocity = 0;
                minecartVelocities.put(entityId, velocity);
                break;
            }

            // Apply accelerator boost when entering an accelerator rail block (multiplier)
            if (newSnap.isAccelerator && (currentSnap == null || newSnap.blockX != currentSnap.blockX || newSnap.blockZ != currentSnap.blockZ)) {
                velocity *= MinecartConfig.getAcceleratorBoost();
                minecartVelocities.put(entityId, velocity);
            }

            // For straight rails: maintain current world movement direction (already aligned at tick start)
            // For corners: adjust direction to follow the corner
            double newSnapHorizLen = Math.sqrt(newSnap.dirX * newSnap.dirX + newSnap.dirZ * newSnap.dirZ);
            if (newSnapHorizLen > 0.01) {
                double newNormX = newSnap.dirX / newSnapHorizLen;
                double newNormZ = newSnap.dirZ / newSnapHorizLen;

                // Dot product of world movement direction with new rail direction
                double worldDot = worldMoveX * newNormX + worldMoveZ * newNormZ;

                // Handle T-junctions and corners using unified edge-based logic
                if (newSnap.isTJunction || newSnap.isCorner) {
                    // Determine entry edge from current movement direction
                    int entryEdge = getEntryEdge(worldMoveX, worldMoveZ);
                    int movementExitEdge = getOppositeEdge(entryEdge);  // Edge we're moving towards
                    int exitEdge = -1;

                    // IMPORTANT: If we're already moving towards a connected edge, just continue through!
                    // This handles the case where we've already turned and are exiting the corner.
                    if (newSnap.connectedEdges[movementExitEdge] && hasRailInDirection(world, newSnap, movementExitEdge)) {
                        // Already moving towards a valid exit - continue without re-computing
                        LOGGER.atInfo().log("[MinecartPhysics] Cart %d: Continuing through %s at (%d,%d,%d) towards %s",
                            entityId, newSnap.isCorner ? "Corner" : "T-junction",
                            newSnap.blockX, newSnap.blockY, newSnap.blockZ, getEdgeName(movementExitEdge));
                        // Use step position to continue through
                        newX = stepX;
                        newY = newSnap.y;
                        newZ = stepZ;
                        currentSnap = newSnap;
                        continue;
                    }

                    LOGGER.atInfo().log("[MinecartPhysics] Cart %d: Entering %s at (%d,%d,%d), entry from %s, edges: W=%b E=%b S=%b N=%b",
                        entityId, newSnap.isTJunction ? "T-junction" : "Corner",
                        newSnap.blockX, newSnap.blockY, newSnap.blockZ, getEdgeName(entryEdge),
                        newSnap.connectedEdges[EDGE_WEST], newSnap.connectedEdges[EDGE_EAST],
                        newSnap.connectedEdges[EDGE_SOUTH], newSnap.connectedEdges[EDGE_NORTH]);

                    if (newSnap.isTJunction) {
                        // T-junction: try straight first, then right, then left
                        int straightEdge = getOppositeEdge(entryEdge);
                        int rightEdge = turnRight(straightEdge);
                        int leftEdge = turnLeft(straightEdge);

                        // Check straight first (using actual rail connectivity)
                        if (newSnap.connectedEdges[straightEdge] && hasRailInDirection(world, newSnap, straightEdge)) {
                            exitEdge = straightEdge;
                            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: T-junction going STRAIGHT to %s",
                                entityId, getEdgeName(exitEdge));
                        } else if (newSnap.connectedEdges[rightEdge] && hasRailInDirection(world, newSnap, rightEdge)) {
                            exitEdge = rightEdge;
                            velocity *= MinecartConfig.getCornerFriction();  // Apply friction for turn
                            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: T-junction turning RIGHT to %s",
                                entityId, getEdgeName(exitEdge));
                        } else if (newSnap.connectedEdges[leftEdge] && hasRailInDirection(world, newSnap, leftEdge)) {
                            exitEdge = leftEdge;
                            velocity *= MinecartConfig.getCornerFriction();  // Apply friction for turn
                            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: T-junction turning LEFT to %s",
                                entityId, getEdgeName(exitEdge));
                        }
                    } else {
                        // Corner: exit through the edge that's connected but isn't our entry
                        for (int edge = 0; edge < 4; edge++) {
                            if (newSnap.connectedEdges[edge] && edge != entryEdge && hasRailInDirection(world, newSnap, edge)) {
                                exitEdge = edge;
                                break;
                            }
                        }
                        velocity *= MinecartConfig.getCornerFriction();  // Always apply friction for corners
                        if (exitEdge >= 0) {
                            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: Corner exit to %s",
                                entityId, getEdgeName(exitEdge));
                        }
                    }

                    if (exitEdge >= 0) {
                        // Valid exit found - update direction
                        double[] exitDir = getEdgeDirection(exitEdge);
                        worldMoveX = exitDir[0];
                        worldMoveZ = exitDir[1];

                        // Keep velocity as magnitude
                        velocity = Math.abs(velocity);
                        minecartVelocities.put(entityId, velocity);
                        minecartWorldDirection.put(entityId, new double[]{worldMoveX, worldMoveZ});

                        // When turning at a corner, snap to center and CONTINUE stepping in new direction
                        // This allows smooth movement through corners instead of teleporting
                        if (exitEdge != getOppositeEdge(entryEdge)) {
                            // Snap to corner center, then continue stepping in new direction
                            newX = newSnap.blockX + 0.5;
                            newY = newSnap.y;
                            newZ = newSnap.blockZ + 0.5;
                            currentSnap = newSnap;
                            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: Turning at corner to %s, continuing from center (%.2f, %.2f, %.2f)",
                                entityId, getEdgeName(exitEdge), newX, newY, newZ);
                            continue;  // Continue stepping in new direction instead of breaking
                        }
                        // Going straight through junction - use step position, NOT snap position
                        // This prevents the cart from getting stuck at the junction center
                        LOGGER.atInfo().log("[MinecartPhysics] Cart %d: Going STRAIGHT through %s, using step position (%.2f, %.2f, %.2f)",
                            entityId, newSnap.isTJunction ? "T-junction" : "corner", stepX, stepY, stepZ);
                        newX = stepX;
                        newY = newSnap.y;  // Keep Y from snap for proper rail height
                        newZ = stepZ;
                        currentSnap = newSnap;
                        continue;  // Continue to next step without overwriting with snap position
                    } else {
                        // No valid exit - dead end
                        LOGGER.atInfo().log("[MinecartPhysics] Cart %d: DEAD END at (%d,%d,%d) - no valid exit",
                            entityId, newSnap.blockX, newSnap.blockY, newSnap.blockZ);
                        velocity = 0;
                        minecartVelocities.put(entityId, velocity);
                        break;
                    }
                } else {
                    // STRAIGHT RAIL: Check if cart can continue in its current direction
                    // The railDir from config may not match visual orientation, so we check
                    // for actual rails in the cart's movement direction first

                    // Determine the edge the cart is moving towards
                    int currentMoveEdge;
                    if (Math.abs(worldMoveX) > Math.abs(worldMoveZ)) {
                        currentMoveEdge = worldMoveX > 0 ? EDGE_EAST : EDGE_WEST;
                    } else {
                        currentMoveEdge = worldMoveZ > 0 ? EDGE_SOUTH : EDGE_NORTH;
                    }

                    // Check if there's a rail in the cart's current direction
                    boolean hasRailAhead = hasRailInDirection(world, newSnap, currentMoveEdge);

                    if (!hasRailAhead) {
                        // No rail ahead in current direction - check if we need to turn
                        // This handles the case of entering a perpendicular rail (e.g., accelerator)
                        double worldDotRail = worldMoveX * newNormX + worldMoveZ * newNormZ;

                        if (Math.abs(worldDotRail) < 0.3) {
                            // Moving perpendicular to rail segment AND no rail ahead
                            // Must align to rail to continue

                            double positiveDirX = newNormX;
                            double positiveDirZ = newNormZ;
                            double negativeDirX = -newNormX;
                            double negativeDirZ = -newNormZ;

                            // Check for rails along the rail's direction
                            int posEdge, negEdge;
                            if (Math.abs(positiveDirX) > Math.abs(positiveDirZ)) {
                                posEdge = positiveDirX > 0 ? EDGE_EAST : EDGE_WEST;
                                negEdge = positiveDirX > 0 ? EDGE_WEST : EDGE_EAST;
                            } else {
                                posEdge = positiveDirZ > 0 ? EDGE_SOUTH : EDGE_NORTH;
                                negEdge = positiveDirZ > 0 ? EDGE_NORTH : EDGE_SOUTH;
                            }

                            boolean hasPosRail = hasRailInDirection(world, newSnap, posEdge);
                            boolean hasNegRail = hasRailInDirection(world, newSnap, negEdge);

                            // Choose direction: prefer the one with a rail, else positive direction
                            if (hasPosRail && !hasNegRail) {
                                worldMoveX = positiveDirX;
                                worldMoveZ = positiveDirZ;
                            } else if (hasNegRail && !hasPosRail) {
                                worldMoveX = negativeDirX;
                                worldMoveZ = negativeDirZ;
                            } else if (hasPosRail || hasNegRail) {
                                // Both have rails - use positive direction
                                worldMoveX = positiveDirX;
                                worldMoveZ = positiveDirZ;
                            } else {
                                // No rails in either direction - dead end, stop
                                velocity = 0;
                                minecartVelocities.put(entityId, velocity);
                                LOGGER.atInfo().log("[MinecartPhysics] Cart %d: Dead end - no rail ahead or to sides",
                                    entityId);
                                break;
                            }

                            // Normalize to cardinal direction
                            if (Math.abs(worldMoveX) > Math.abs(worldMoveZ)) {
                                worldMoveX = Math.signum(worldMoveX);
                                worldMoveZ = 0;
                            } else {
                                worldMoveX = 0;
                                worldMoveZ = Math.signum(worldMoveZ);
                            }

                            minecartWorldDirection.put(entityId, new double[]{worldMoveX, worldMoveZ});
                            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: No rail ahead, turning to follow rail: (%.1f, %.1f)",
                                entityId, worldMoveX, worldMoveZ);
                        }
                    }

                    // Keep velocity as magnitude
                    if (velocity < 0) {
                        velocity = Math.abs(velocity);
                        minecartVelocities.put(entityId, velocity);
                    }
                }
            }

            // Update position and snap for next step
            newX = newSnap.x;
            newY = newSnap.y;
            newZ = newSnap.z;
            currentSnap = newSnap;
        }

        if (newSnap == null) {
            // No rail found - stop at current position
            newX = snap.x;
            newY = snap.y;
            newZ = snap.z;
            velocity = 0;
            minecartVelocities.put(entityId, velocity);
            minecartWorldDirection.remove(entityId); // Clear direction when stopped
        }

        // Use the final snap from sub-stepping (or original if no movement)
        RailSnap finalSnap = (newSnap != null) ? currentSnap : snap;

        // Update persisted world direction at end of tick (unless stopped)
        if (Math.abs(velocity) > MinecartConfig.getMinSpeed()) {
            minecartWorldDirection.put(entityId, new double[]{worldMoveX, worldMoveZ});
        } else {
            // Cart stopped - clear direction so it re-initializes on next push
            minecartWorldDirection.remove(entityId);
        }

        // Update rotation - instant snap to movement direction
        if (Math.abs(velocity) > MinecartConfig.getMinSpeed()) {
            // Use world movement direction for rotation (not rail segment direction)
            float targetYaw = (float) Math.atan2(worldMoveX, worldMoveZ);
            rotation.setYaw(targetYaw);
            minecartFacingYaw.put(entityId, targetYaw);

            // Pitch based on slope
            float targetPitch = 0;
            if (finalSnap.isSlope) {
                // Determine if going uphill or downhill based on world direction vs slope direction
                double horizDot = worldMoveX * finalSnap.dirX + worldMoveZ * finalSnap.dirZ;
                // dirY is negative for downhill in the positive rail direction
                // If horizDot >= 0, we're moving in the rail's positive direction
                double effectiveDirY = (horizDot >= 0) ? finalSnap.dirY : -finalSnap.dirY;
                targetPitch = (float) Math.asin(-effectiveDirY);
            }
            rotation.setPitch(targetPitch);
        }

        transform.getPosition().x = newX;
        transform.getPosition().y = newY;
        transform.getPosition().z = newZ;

        if (shouldLog) {
            LOGGER.atInfo().log("[MinecartPhysics] Cart %d: vel=%.2f, pos=(%.2f,%.2f,%.2f), railDir=(%.2f,%.2f,%.2f), block=(%d,%d,%d), slope=%b, steps=%d",
                entityId, velocity, newX, newY, newZ, finalSnap.dirX, finalSnap.dirY, finalSnap.dirZ,
                finalSnap.blockX, finalSnap.blockY, finalSnap.blockZ, finalSnap.isSlope, numSteps);
        }
    }

    private RailSnap findBestRailSnapWithDirection(World world, Vector3d position, float prefDirX, float prefDirZ) {
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y);
        int blockZ = (int) Math.floor(position.z);

        RailSnap bestSnap = null;
        double bestScore = Double.MAX_VALUE;

        // Normalize preferred direction
        double prefLen = Math.sqrt(prefDirX * prefDirX + prefDirZ * prefDirZ);
        double prefNormX = prefLen > 0.01 ? prefDirX / prefLen : 0;
        double prefNormZ = prefLen > 0.01 ? prefDirZ / prefLen : 0;
        boolean hasPreferredDir = prefLen > 0.01;

        // Track the current block's rail - but don't auto-return, let it compete in scoring
        // This allows proper corner entry checking
        RailSnap currentBlockSnap = snapToRailAt(world, position, blockX, blockY, blockZ, prefNormX, prefNormZ);
        if (currentBlockSnap != null && currentBlockSnap.distanceSq <= 0.25) {
            // Very close to current block's rail (within 0.5 blocks) - likely still on it
            // Only return early if distance is very small
            return currentBlockSnap;
        }

        // First search: Only immediately adjacent blocks (distance 1)
        // This ensures we find connected rails before looking farther
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    RailSnap snap = snapToRailAt(world, position, blockX + dx, blockY + dy, blockZ + dz, prefNormX, prefNormZ);
                    if (snap != null && snap.distanceSq <= 1.5) {
                        double score = snap.distanceSq;

                        if (hasPreferredDir) {
                            // Penalize rails that are behind the cart
                            double toBlockX = (snap.blockX + 0.5) - position.x;
                            double toBlockZ = (snap.blockZ + 0.5) - position.z;
                            double toBlockLen = Math.sqrt(toBlockX * toBlockX + toBlockZ * toBlockZ);

                            if (toBlockLen > 0.3) {
                                double toBlockNormX = toBlockX / toBlockLen;
                                double toBlockNormZ = toBlockZ / toBlockLen;
                                double behindDot = prefNormX * toBlockNormX + prefNormZ * toBlockNormZ;

                                // Heavy penalty for rails behind us
                                if (behindDot < -0.3) {
                                    score += snap.isSlope ? 20.0 : 10.0;
                                }
                                // NO bonus for rails ahead - this prevents skipping corners
                            }

                            // Small penalty for perpendicular rails to prefer straight continuation
                            double snapLen = Math.sqrt(snap.dirX * snap.dirX + snap.dirZ * snap.dirZ);
                            if (snapLen > 0.01) {
                                double snapNormX = snap.dirX / snapLen;
                                double snapNormZ = snap.dirZ / snapLen;
                                double dirDot = prefNormX * snapNormX + prefNormZ * snapNormZ;
                                double absDot = Math.abs(dirDot);
                                if (absDot < 0.3) {
                                    score += 0.5; // Small penalty for perpendicular
                                }
                            }
                        }

                        if (score < bestScore) {
                            bestScore = score;
                            bestSnap = snap;
                        }
                    }
                }
            }
        }

        // If found an adjacent rail, use it - don't search farther
        if (bestSnap != null) {
            return bestSnap;
        }

        // Second search: Extended radius only if no adjacent rail found
        for (int dy = 1; dy >= -2; dy--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    // Skip already-searched blocks
                    if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1) continue;

                    RailSnap snap = snapToRailAt(world, position, blockX + dx, blockY + dy, blockZ + dz, prefNormX, prefNormZ);
                    if (snap != null && snap.distanceSq <= 2.0) {
                        double score = snap.distanceSq;

                        // Heavy penalty for distant rails - prefer to stop rather than teleport
                        score += 5.0;

                        if (hasPreferredDir) {
                            double toBlockX = (snap.blockX + 0.5) - position.x;
                            double toBlockZ = (snap.blockZ + 0.5) - position.z;
                            double toBlockLen = Math.sqrt(toBlockX * toBlockX + toBlockZ * toBlockZ);

                            if (toBlockLen > 0.3) {
                                double toBlockNormX = toBlockX / toBlockLen;
                                double toBlockNormZ = toBlockZ / toBlockLen;
                                double behindDot = prefNormX * toBlockNormX + prefNormZ * toBlockNormZ;

                                if (behindDot < -0.3) {
                                    score += 20.0;
                                }
                            }
                        }

                        if (score < bestScore) {
                            bestScore = score;
                            bestSnap = snap;
                        }
                    }
                }
            }
        }

        return bestSnap;
    }

    private RailSnap snapToRailAt(World world, Vector3d entityPos, int blockX, int blockY, int blockZ, double incomingDirX, double incomingDirZ) {
        try {
            BlockType blockType = world.getBlockType(blockX, blockY, blockZ);
            if (blockType == null) return null;

            long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            if (chunk == null) return null;

            int localX = blockX & 15;
            int localZ = blockZ & 15;

            // Get block rotation using World.getBlockRotationIndex (the proper API)
            int rotationIndex = world.getBlockRotationIndex(blockX, blockY, blockZ);

            // Extract block ID from toString (format: "BlockType{id=*Rail_State_Definitions_T, ...}")
            // Block ID tells us the rail type: "_T" = T-junction, "_Corner" = corner, "Accel" = accelerator, else straight
            String blockTypeName = blockType.toString();
            String blockId = null;
            boolean isTByName = false;
            boolean isAccelByName = false;
            if (blockTypeName != null) {
                int idStart = blockTypeName.indexOf("id=");
                if (idStart >= 0) {
                    idStart += 3; // Skip "id="
                    int idEnd = blockTypeName.indexOf(",", idStart);
                    if (idEnd < 0) idEnd = blockTypeName.indexOf("}", idStart);
                    if (idEnd > idStart) {
                        blockId = blockTypeName.substring(idStart, idEnd).trim();
                        isTByName = blockId.endsWith("_T");
                        isAccelByName = blockId.contains("Accel") || blockId.contains("_Rail_Accel");
                    }
                }
            }

            RailConfig railConfig = blockType.getRailConfig(rotationIndex);
            if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
                for (int rot = 0; rot < 4; rot++) {
                    railConfig = blockType.getRailConfig(rot);
                    if (railConfig != null && railConfig.points != null && railConfig.points.length >= 2) {
                        break;
                    }
                }
            }

            if (railConfig == null || railConfig.points == null || railConfig.points.length < 2) {
                return null;
            }

            RailPoint[] points = railConfig.points;
            float rawDy = points[points.length - 1].point.y - points[0].point.y;
            boolean isSlope = Math.abs(rawDy) > 0.1f;

            float dirX, dirY, dirZ;
            double snapX, snapY, snapZ;

            // Compute effective rotation for flat rails BEFORE snapping
            // This is needed both for snapping AND for edge detection
            int effectiveRotation = 0;
            boolean looksLikeCorner = false;
            boolean isCornerByName = blockId != null && blockId.contains("_Corner");

            if (!isSlope) {
                looksLikeCorner = Math.abs(points[0].point.x - points[points.length-1].point.x) > 0.1
                               && Math.abs(points[0].point.z - points[points.length-1].point.z) > 0.1;

                if (looksLikeCorner || isCornerByName) {
                    // Corners: raw points from getRailConfig are ALREADY oriented correctly
                    // for the visual appearance, regardless of rotationIndex. No rotation needed.
                    effectiveRotation = 0;
                } else {
                    // Straight flat rails: Compare raw point orientation with neighbor expectations
                    // getRailConfig may return differently oriented points based on rotationIndex
                    // (e.g., rot=0 gives Z-aligned, rot=1 gives X-aligned)
                    // Only rotate if raw orientation doesn't match what neighbors expect

                    // Determine raw point orientation
                    float rawDx = Math.abs(points[points.length-1].point.x - points[0].point.x);
                    float rawDz = Math.abs(points[points.length-1].point.z - points[0].point.z);
                    boolean rawIsXAligned = rawDx > rawDz;

                    // Determine desired orientation from neighbors
                    int neighborDir = detectFlatRailDirectionFromNeighbors(world, blockX, blockY, blockZ);
                    boolean desiredIsXAligned = (neighborDir == 1);

                    // Only rotate if raw and desired don't match
                    if (rawIsXAligned != desiredIsXAligned) {
                        effectiveRotation = 1; // 90° rotation to fix alignment
                    } else {
                        effectiveRotation = 0; // No rotation needed, already correct
                    }
                }
            }

            if (isSlope) {
                // For slopes: detect direction from neighboring blocks since getRailConfig
                // returns unrotated points regardless of actual block rotation
                int downhillDir = detectSlopeDirectionFromNeighbors(world, blockX, blockY, blockZ);

                // Direction vectors for downhill movement (normalized, 45° slope)
                // 0=+Z, 1=+X, 2=-Z, 3=-X
                switch (downhillDir) {
                    case 0: dirX = 0; dirY = -0.707f; dirZ = 0.707f; break;
                    case 1: dirX = 0.707f; dirY = -0.707f; dirZ = 0; break;
                    case 2: dirX = 0; dirY = -0.707f; dirZ = -0.707f; break;
                    case 3: dirX = -0.707f; dirY = -0.707f; dirZ = 0; break;
                    default: dirX = 0; dirY = -0.707f; dirZ = 0.707f; break;
                }

                // Compute snap position: slope runs from high point to low point
                // Using actual rail Y offsets (1.1 for high, 0.1 for low based on rail data)
                double highX, highY, highZ, lowX, lowY, lowZ;
                switch (downhillDir) {
                    case 0: // +Z downhill
                        highX = blockX + 0.5; highY = blockY + 1.1; highZ = blockZ + 0.0;
                        lowX = blockX + 0.5; lowY = blockY + 0.1; lowZ = blockZ + 1.0;
                        break;
                    case 1: // +X downhill
                        highX = blockX + 0.0; highY = blockY + 1.1; highZ = blockZ + 0.5;
                        lowX = blockX + 1.0; lowY = blockY + 0.1; lowZ = blockZ + 0.5;
                        break;
                    case 2: // -Z downhill
                        highX = blockX + 0.5; highY = blockY + 1.1; highZ = blockZ + 1.0;
                        lowX = blockX + 0.5; lowY = blockY + 0.1; lowZ = blockZ + 0.0;
                        break;
                    case 3: // -X downhill
                        highX = blockX + 1.0; highY = blockY + 1.1; highZ = blockZ + 0.5;
                        lowX = blockX + 0.0; lowY = blockY + 0.1; lowZ = blockZ + 0.5;
                        break;
                    default:
                        highX = blockX + 0.5; highY = blockY + 1.1; highZ = blockZ + 0.0;
                        lowX = blockX + 0.5; lowY = blockY + 0.1; lowZ = blockZ + 1.0;
                        break;
                }

                // Project entity onto slope segment
                double segX = lowX - highX;
                double segY = lowY - highY;
                double segZ = lowZ - highZ;
                double segLenSq = segX * segX + segY * segY + segZ * segZ;

                double t = 0.5;
                if (segLenSq > 0.0001) {
                    t = ((entityPos.x - highX) * segX + (entityPos.y - highY) * segY + (entityPos.z - highZ) * segZ) / segLenSq;
                }

                // If cart is past the ends of the slope, don't snap to it
                if (t < -0.15 || t > 1.15) {
                    return null; // Cart has moved past this slope
                }

                // Also check if cart's Y is significantly below the slope's low point
                // This prevents snapping back to a slope the cart has exited
                double minSlopeY = Math.min(highY, lowY);
                if (entityPos.y < minSlopeY - 0.3) {
                    return null; // Cart is below the slope
                }

                t = Math.max(0, Math.min(1, t));
                snapX = highX + t * segX;
                snapY = highY + t * segY;
                snapZ = highZ + t * segZ;

            } else {
                // For flat rails (including corners): find closest segment and use its direction
                // getRailConfig returns unrotated points, so we must rotate them based on rotationIndex
                // rotationIndex: 0=no rotation, 1=90° CW, 2=180°, 3=270° CW
                // effectiveRotation and looksLikeCorner already computed above

                double bestEffectiveDistSq = Double.MAX_VALUE;
                double bestActualDistSq = Double.MAX_VALUE;
                snapX = entityPos.x;
                snapY = entityPos.y;
                snapZ = entityPos.z;
                dirX = 1;
                dirY = 0;
                dirZ = 0;
                boolean foundValidSnap = false;

                // Find closest point on rail segments
                // Apply rotation transform to each point based on effectiveRotation
                // For T-junctions and corners, prefer segments aligned with incoming direction
                boolean hasIncomingDir = Math.abs(incomingDirX) > 0.01 || Math.abs(incomingDirZ) > 0.01;

                for (int i = 0; i < points.length - 1; i++) {
                    // Get rotated coordinates
                    double[] rot1 = rotatePoint(points[i].point.x, points[i].point.z, effectiveRotation);
                    double[] rot2 = rotatePoint(points[i + 1].point.x, points[i + 1].point.z, effectiveRotation);

                    double px1 = blockX + rot1[0];
                    double py1 = blockY + points[i].point.y;
                    double pz1 = blockZ + rot1[1];
                    double px2 = blockX + rot2[0];
                    double py2 = blockY + points[i + 1].point.y;
                    double pz2 = blockZ + rot2[1];

                    double sX = px2 - px1;
                    double sY = py2 - py1;
                    double sZ = pz2 - pz1;
                    double sLenSq = sX * sX + sY * sY + sZ * sZ;

                    if (sLenSq < 0.0001) continue;

                    double st = ((entityPos.x - px1) * sX + (entityPos.y - py1) * sY + (entityPos.z - pz1) * sZ) / sLenSq;

                    // Skip if cart is past this segment (allow small tolerance)
                    if (st < -0.2 || st > 1.2) continue;

                    st = Math.max(0, Math.min(1, st));

                    double cX = px1 + st * sX;
                    double cY = py1 + st * sY;
                    double cZ = pz1 + st * sZ;

                    double ddx = cX - entityPos.x;
                    double ddy = cY - entityPos.y;
                    double ddz = cZ - entityPos.z;
                    double distSq = ddx * ddx + ddy * ddy + ddz * ddz;

                    // For T-junctions, SKIP perpendicular segments entirely (not just penalize)
                    // This ensures the cart follows the intended path through the T-junction
                    if (isTByName && hasIncomingDir) {
                        double segLen = Math.sqrt(sX * sX + sZ * sZ);
                        if (segLen > 0.01) {
                            double segNormX = sX / segLen;
                            double segNormZ = sZ / segLen;
                            double alignDot = Math.abs(incomingDirX * segNormX + incomingDirZ * segNormZ);
                            // Skip perpendicular segments entirely for T-junctions
                            if (alignDot < 0.3) {
                                continue;
                            }
                        }
                    }

                    // For corners, add penalty for perpendicular segments (but don't skip, as corners need more flexibility)
                    double effectiveDistSq = distSq;
                    if (looksLikeCorner && hasIncomingDir) {
                        double segLen = Math.sqrt(sX * sX + sZ * sZ);
                        if (segLen > 0.01) {
                            double segNormX = sX / segLen;
                            double segNormZ = sZ / segLen;
                            double alignDot = Math.abs(incomingDirX * segNormX + incomingDirZ * segNormZ);
                            if (alignDot < 0.5) {
                                effectiveDistSq += 5.0; // Large penalty for perpendicular/misaligned
                            }
                        }
                    }

                    if (effectiveDistSq < bestEffectiveDistSq) {
                        bestEffectiveDistSq = effectiveDistSq;
                        bestActualDistSq = distSq; // Store the actual geometric distance
                        snapX = cX;
                        snapY = cY;
                        snapZ = cZ;
                        foundValidSnap = true;

                        // Calculate direction from THIS segment (important for corners!)
                        double segLen = Math.sqrt(sLenSq);
                        dirX = (float) (sX / segLen);
                        dirY = (float) (sY / segLen);
                        dirZ = (float) (sZ / segLen);
                    }
                }

                // If no valid snap found on segments, check if we should still snap for T-junctions
                // T-junctions may only have one segment in getRailConfig (e.g., E-W bar), but cart
                // may approach from perpendicular direction (N-S). In this case, snap to center.
                if (!foundValidSnap) {
                    if (isTByName) {
                        // For T-junctions with no aligned segment (approaching perpendicular),
                        // DON'T snap to center - keep entity's X/Z position but use rail Y.
                        // Snapping to center would pull the cart backward every tick!
                        double centerX = blockX + 0.5;
                        double centerZ = blockZ + 0.5;
                        double centerY = blockY + 0.1; // Rail height
                        double toCenterX = centerX - entityPos.x;
                        double toCenterZ = centerZ - entityPos.z;
                        double toCenterDistSq = toCenterX * toCenterX + toCenterZ * toCenterZ;

                        // Accept if within ~1 block of center
                        if (toCenterDistSq < 1.5) {
                            // Keep entity's X/Z, only adjust Y to rail height
                            snapX = entityPos.x;
                            snapY = centerY;
                            snapZ = entityPos.z;
                            // Direction will be determined by edge logic in tick method
                            dirX = (float) incomingDirX;
                            dirY = 0;
                            dirZ = (float) incomingDirZ;
                            foundValidSnap = true;
                        }
                    }

                    if (!foundValidSnap) {
                        return null;
                    }
                }

                // Don't reject corners here - let the tick method handle exit direction
                // The edge-based logic will determine proper exit direction
            }

            double distDx = snapX - entityPos.x;
            double distDy = snapY - entityPos.y;
            double distDz = snapZ - entityPos.z;
            double distSq = distDx * distDx + distDy * distDy + distDz * distDz;

            // Detect rail type and connected edges using BLOCK ID and RAIL POINTS
            // NOT neighbor detection - a neighboring rail doesn't mean connection!
            boolean cornerFlag = false;
            boolean tJunctionFlag = false;
            boolean[] connectedEdges = new boolean[4]; // [WEST, EAST, SOUTH, NORTH]

            if (!isSlope) {
                // Determine rail type from block ID
                // isCornerByName already computed above

                if (isTByName) {
                    // T-JUNCTION: Use rotation-based edges
                    // Based on in-game verification:
                    // Rotation 0: stem SOUTH, bar E-W, closed NORTH
                    // Rotation 1: stem EAST, bar N-S, closed WEST
                    // Rotation 2: stem NORTH, bar E-W, closed SOUTH
                    // Rotation 3: stem WEST, bar N-S, closed EAST
                    tJunctionFlag = true;
                    switch (rotationIndex) {
                        case 0:
                            connectedEdges[EDGE_SOUTH] = true;
                            connectedEdges[EDGE_EAST] = true;
                            connectedEdges[EDGE_WEST] = true;
                            break;
                        case 1:
                            connectedEdges[EDGE_EAST] = true;
                            connectedEdges[EDGE_NORTH] = true;
                            connectedEdges[EDGE_SOUTH] = true;
                            break;
                        case 2:
                            connectedEdges[EDGE_NORTH] = true;
                            connectedEdges[EDGE_EAST] = true;
                            connectedEdges[EDGE_WEST] = true;
                            break;
                        case 3:
                            connectedEdges[EDGE_WEST] = true;
                            connectedEdges[EDGE_NORTH] = true;
                            connectedEdges[EDGE_SOUTH] = true;
                            break;
                    }
                } else if (isCornerByName || looksLikeCorner) {
                    // CORNER: Detected by block ID containing "_Corner" OR by geometry
                    // (endpoints not aligned on either axis = corner shape)
                    // Rail points are in block-local coords (0-1), endpoints tell us which edges connect
                    // Apply effectiveRotation to points if rotationIndex was 0
                    cornerFlag = true;
                    RailPoint firstPt = points[0];
                    RailPoint lastPt = points[points.length - 1];

                    // Apply rotation to get actual edge positions
                    double[] rotFirst = rotatePoint(firstPt.point.x, firstPt.point.z, effectiveRotation);
                    double[] rotLast = rotatePoint(lastPt.point.x, lastPt.point.z, effectiveRotation);

                    // Determine which edge each endpoint is at (using rotated coordinates)
                    connectedEdges[getEdgeFromPoint((float)rotFirst[0], (float)rotFirst[1])] = true;
                    connectedEdges[getEdgeFromPoint((float)rotLast[0], (float)rotLast[1])] = true;
                } else {
                    // STRAIGHT RAIL: Determine edges from rail point endpoints
                    // A straight rail connects two opposite edges
                    // IMPORTANT: Apply effectiveRotation to points if rotationIndex was 0!
                    RailPoint firstPt = points[0];
                    RailPoint lastPt = points[points.length - 1];

                    // Apply rotation to get actual edge positions
                    double[] rotFirst = rotatePoint(firstPt.point.x, firstPt.point.z, effectiveRotation);
                    double[] rotLast = rotatePoint(lastPt.point.x, lastPt.point.z, effectiveRotation);

                    connectedEdges[getEdgeFromPoint((float)rotFirst[0], (float)rotFirst[1])] = true;
                    connectedEdges[getEdgeFromPoint((float)rotLast[0], (float)rotLast[1])] = true;
                }
            }

            return new RailSnap(snapX, snapY, snapZ, dirX, dirY, dirZ, distSq, blockX, blockY, blockZ, isSlope, cornerFlag, tJunctionFlag, isAccelByName, connectedEdges);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Detect slope direction by examining neighboring blocks.
     * Returns: 0=+Z downhill, 1=+X downhill, 2=-Z downhill, 3=-X downhill
     */
    private int detectSlopeDirectionFromNeighbors(World world, int x, int y, int z) {
        // Check each direction for a rail or slope at Y-1 level (bottom of slope)
        // The direction where we find a lower rail is the downhill direction
        int[][] dirs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}}; // +Z, +X, -Z, -X

        // Strategy 1: Look for rail at Y-1 in each direction (bottom connection)
        for (int i = 0; i < 4; i++) {
            int nx = x + dirs[i][0];
            int nz = z + dirs[i][1];
            BlockType belowType = world.getBlockType(nx, y - 1, nz);
            if (belowType != null) {
                RailConfig rail = belowType.getRailConfig(0);
                if (rail != null && rail.points != null && rail.points.length >= 2) {
                    return i; // Found rail below in this direction = downhill
                }
            }
        }

        // Strategy 2: Look for slope rail at Y-1 in each direction (continuing slope)
        for (int i = 0; i < 4; i++) {
            int nx = x + dirs[i][0];
            int nz = z + dirs[i][1];
            BlockType adjType = world.getBlockType(nx, y - 1, nz);
            if (adjType != null) {
                RailConfig rail = adjType.getRailConfig(0);
                if (rail != null && rail.points != null && rail.points.length >= 2) {
                    float yDiff = Math.abs(rail.points[0].point.y - rail.points[rail.points.length - 1].point.y);
                    if (yDiff > 0.1f) { // It's a slope
                        return i;
                    }
                }
            }
        }

        // Strategy 3: Look for flat rail at same Y level - flat connects to slope's LOW point
        // So the downhill direction is TOWARDS the flat rail (same direction i)
        for (int i = 0; i < 4; i++) {
            int nx = x + dirs[i][0];
            int nz = z + dirs[i][1];
            BlockType adjType = world.getBlockType(nx, y, nz);
            if (adjType != null) {
                RailConfig rail = adjType.getRailConfig(0);
                if (rail != null && rail.points != null && rail.points.length >= 2) {
                    float yDiff = Math.abs(rail.points[0].point.y - rail.points[rail.points.length - 1].point.y);
                    if (yDiff < 0.1f) { // It's flat
                        return i; // Downhill direction is towards the flat rail
                    }
                }
            }
        }

        // Strategy 4: Look for slope at Y+1 level (connecting from above at high point)
        // The adjacent slope's low point connects to our high point, so downhill is opposite
        for (int i = 0; i < 4; i++) {
            int nx = x + dirs[i][0];
            int nz = z + dirs[i][1];
            BlockType aboveType = world.getBlockType(nx, y + 1, nz);
            if (aboveType != null) {
                RailConfig rail = aboveType.getRailConfig(0);
                if (rail != null && rail.points != null && rail.points.length >= 2) {
                    return (i + 2) % 4; // Opposite direction is downhill
                }
            }
        }

        // Default: +Z downhill
        return 0;
    }

    /**
     * Detect flat rail direction by examining neighboring blocks.
     * Returns: 0=Z-aligned (no rotation), 1=X-aligned (90° rotation)
     */
    private int detectFlatRailDirectionFromNeighbors(World world, int x, int y, int z) {
        // Check which directions have connecting rails
        // If we find rails in +X or -X directions but not in +Z/-Z, rail is X-aligned (rotation 1)
        // If we find rails in +Z or -Z directions but not in +X/-X, rail is Z-aligned (rotation 0)

        boolean hasRailPosX = hasRailAt(world, x + 1, y, z) || hasRailAt(world, x + 1, y - 1, z) || hasRailAt(world, x + 1, y + 1, z);
        boolean hasRailNegX = hasRailAt(world, x - 1, y, z) || hasRailAt(world, x - 1, y - 1, z) || hasRailAt(world, x - 1, y + 1, z);
        boolean hasRailPosZ = hasRailAt(world, x, y, z + 1) || hasRailAt(world, x, y - 1, z + 1) || hasRailAt(world, x, y + 1, z + 1);
        boolean hasRailNegZ = hasRailAt(world, x, y, z - 1) || hasRailAt(world, x, y - 1, z - 1) || hasRailAt(world, x, y + 1, z - 1);

        boolean xAxis = hasRailPosX || hasRailNegX;
        boolean zAxis = hasRailPosZ || hasRailNegZ;

        // If X-axis connections found but no Z-axis, rail runs in X direction
        if (xAxis && !zAxis) {
            return 1; // 90° rotation = X-aligned
        }
        // If Z-axis connections found but no X-axis, rail runs in Z direction
        if (zAxis && !xAxis) {
            return 0; // No rotation = Z-aligned
        }
        // If both or neither, check for slope connections which are more reliable
        // A slope at Y+1 or Y-1 in a direction tells us where this rail connects

        // Check for slopes at each direction
        for (int dir = 0; dir < 4; dir++) {
            int dx = (dir == 1) ? 1 : (dir == 3) ? -1 : 0;
            int dz = (dir == 0) ? 1 : (dir == 2) ? -1 : 0;

            // Check for slope at same level connecting to us
            BlockType adjType = world.getBlockType(x + dx, y, z + dz);
            if (adjType != null) {
                RailConfig adjRail = adjType.getRailConfig(0);
                if (adjRail != null && adjRail.points != null && adjRail.points.length >= 2) {
                    float yDiff = Math.abs(adjRail.points[0].point.y - adjRail.points[adjRail.points.length - 1].point.y);
                    if (yDiff > 0.1f) {
                        // This is a slope - flat rail connects to it
                        return (dir == 1 || dir == 3) ? 1 : 0; // X-dir slopes = X-aligned rail, Z-dir slopes = Z-aligned
                    }
                }
            }

            // Check for slope below
            BlockType belowType = world.getBlockType(x + dx, y - 1, z + dz);
            if (belowType != null) {
                RailConfig belowRail = belowType.getRailConfig(0);
                if (belowRail != null && belowRail.points != null && belowRail.points.length >= 2) {
                    float yDiff = Math.abs(belowRail.points[0].point.y - belowRail.points[belowRail.points.length - 1].point.y);
                    if (yDiff > 0.1f) {
                        return (dir == 1 || dir == 3) ? 1 : 0;
                    }
                }
            }
        }

        // Default to Z-aligned if we can't determine
        return 0;
    }

    /**
     * Check if there's a rail at the given position.
     */
    private boolean hasRailAt(World world, int x, int y, int z) {
        BlockType blockType = world.getBlockType(x, y, z);
        if (blockType == null) return false;
        RailConfig rail = blockType.getRailConfig(0);
        return rail != null && rail.points != null && rail.points.length >= 2;
    }

    /**
     * Rotate a point around the block center (0.5, 0.5) based on rotation index.
     * rotationIndex: 0=no rotation, 1=90° CW, 2=180°, 3=270° CW (or 90° CCW)
     * Returns [rotatedX, rotatedZ]
     */
    private double[] rotatePoint(double x, double z, int rotationIndex) {
        // Center of block is (0.5, 0.5)
        double cx = 0.5, cz = 0.5;
        double dx = x - cx;
        double dz = z - cz;

        double rotX, rotZ;
        switch (rotationIndex % 4) {
            case 0: // No rotation
                rotX = dx;
                rotZ = dz;
                break;
            case 1: // 90° clockwise: (dx, dz) -> (dz, -dx)
                rotX = dz;
                rotZ = -dx;
                break;
            case 2: // 180°: (dx, dz) -> (-dx, -dz)
                rotX = -dx;
                rotZ = -dz;
                break;
            case 3: // 270° clockwise (90° CCW): (dx, dz) -> (-dz, dx)
                rotX = -dz;
                rotZ = dx;
                break;
            default:
                rotX = dx;
                rotZ = dz;
                break;
        }

        return new double[] { cx + rotX, cz + rotZ };
    }

    private static class RailSnap {
        final double x, y, z;
        final float dirX, dirY, dirZ;
        final double distanceSq;
        final int blockX, blockY, blockZ;
        final boolean isSlope;
        final boolean isCorner;
        final boolean isTJunction;
        final boolean isAccelerator;
        // Which edges this rail connects to [WEST, EAST, SOUTH, NORTH]
        final boolean[] connectedEdges;

        RailSnap(double x, double y, double z, float dx, float dy, float dz, double distSq, int bx, int by, int bz, boolean isSlope, boolean isCorner, boolean isTJunction, boolean isAccelerator, boolean[] connectedEdges) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dirX = dx;
            this.dirY = dy;
            this.dirZ = dz;
            this.distanceSq = distSq;
            this.blockX = bx;
            this.blockY = by;
            this.blockZ = bz;
            this.isSlope = isSlope;
            this.isCorner = isCorner;
            this.isTJunction = isTJunction;
            this.isAccelerator = isAccelerator;
            this.connectedEdges = connectedEdges != null ? connectedEdges : new boolean[4];
        }
    }
}
