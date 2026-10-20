package me.lonaldeu.antifreecam;

/** Walks the block grid along a line segment (Amanatides-Woo voxel traversal). */
final class Raycast {

    interface Solid {
        boolean at(int x, int y, int z);
    }

    private Raycast() {
    }

    /**
     * True if no solid block lies strictly between the start and end blocks of the segment.
     * The blocks containing the two end points themselves are not checked.
     */
    static boolean clear(double x0, double y0, double z0, double x1, double y1, double z1, Solid solid) {
        int x = floor(x0), y = floor(y0), z = floor(z0);
        int endX = floor(x1), endY = floor(y1), endZ = floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int stepX = (int) Math.signum(dx), stepY = (int) Math.signum(dy), stepZ = (int) Math.signum(dz);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        // Distance (as a fraction of the segment) to the first boundary crossing on each axis.
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? x + 1 - x0 : x0 - x) * deltaX;
        double nextY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? y + 1 - y0 : y0 - y) * deltaY;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? z + 1 - z0 : z0 - z) * deltaZ;

        int steps = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z);
        for (int i = 0; i < steps; i++) {
            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                y += stepY;
                nextY += deltaY;
            } else {
                z += stepZ;
                nextZ += deltaZ;
            }
            if (x == endX && y == endY && z == endZ) return true;
            if (solid.at(x, y, z)) return false;
        }
        return true;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
