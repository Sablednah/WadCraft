package com.sablednah.wadcraft.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import com.sablednah.wadcraft.wad.DoomMap;
import com.sablednah.wadcraft.wad.DoomMap.LineDef;
import com.sablednah.wadcraft.wad.DoomMap.Vertex;

/**
 * "Which linedef is nearest this point?", answered from a coarse grid rather
 * than by scanning every line for every block — the difference between a
 * second and a minute on a big map.
 */
final class LineIndex {

    private static final int BUCKET_BLOCKS = 4;

    private final DoomMap map;
    private final double bucketSize;
    private final double minX, minY;
    private final Map<Long, List<Integer>> buckets = new HashMap<>();

    LineIndex(DoomMap map, double minX, double minY, double scale) {
        this.map = map;
        this.minX = minX;
        this.minY = minY;
        this.bucketSize = scale * BUCKET_BLOCKS;
        List<LineDef> lines = map.lines();
        for (int idx = 0; idx < lines.size(); idx++) {
            Vertex a = map.vertices().get(lines.get(idx).v1()), b = map.vertices().get(lines.get(idx).v2());
            int bx0 = bucket(Math.min(a.x(), b.x()) - minX), bx1 = bucket(Math.max(a.x(), b.x()) - minX);
            int by0 = bucket(Math.min(a.y(), b.y()) - minY), by1 = bucket(Math.max(a.y(), b.y()) - minY);
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int by = by0; by <= by1; by++) {
                    buckets.computeIfAbsent(key(bx, by), k -> new ArrayList<>()).add(idx);
                }
            }
        }
    }

    private int bucket(double offset) {
        return (int) Math.floor(offset / bucketSize);
    }

    private static long key(int bx, int by) {
        return ((long) bx << 32) ^ (by & 0xFFFFFFFFL);
    }

    /** The nearest line passing {@code accept}, searching outward ring by ring; null if none close. */
    LineDef nearest(double px, double py, IntPredicate accept) {
        int cx = bucket(px - minX), cy = bucket(py - minY);
        LineDef best = null;
        double bestDist = Double.MAX_VALUE;
        for (int ring = 0; ring <= 3; ring++) {
            for (int bx = cx - ring; bx <= cx + ring; bx++) {
                for (int by = cy - ring; by <= cy + ring; by++) {
                    if (Math.max(Math.abs(bx - cx), Math.abs(by - cy)) != ring) continue;
                    List<Integer> list = buckets.get(key(bx, by));
                    if (list == null) continue;
                    for (int idx : list) {
                        if (!accept.test(idx)) continue;
                        double d = distance(map.lines().get(idx), px, py);
                        if (d < bestDist) {
                            bestDist = d;
                            best = map.lines().get(idx);
                        }
                    }
                }
            }
            // Anything found within this ring beats whatever the next ring holds.
            if (best != null && bestDist <= ring * bucketSize) break;
        }
        return best;
    }

    /** Does the segment (ax,ay)-(bx,by) cross a line passing {@code accept}? Returns it, or null. */
    LineDef crossing(double ax, double ay, double bx, double by, IntPredicate accept) {
        int x0 = bucket(Math.min(ax, bx) - minX), x1 = bucket(Math.max(ax, bx) - minX);
        int y0 = bucket(Math.min(ay, by) - minY), y1 = bucket(Math.max(ay, by) - minY);
        for (int bx2 = x0; bx2 <= x1; bx2++) {
            for (int by2 = y0; by2 <= y1; by2++) {
                List<Integer> list = buckets.get(key(bx2, by2));
                if (list == null) continue;
                for (int idx : list) {
                    if (!accept.test(idx)) continue;
                    LineDef l = map.lines().get(idx);
                    Vertex p = map.vertices().get(l.v1()), q = map.vertices().get(l.v2());
                    if (intersects(ax, ay, bx, by, p.x(), p.y(), q.x(), q.y())) return l;
                }
            }
        }
        return null;
    }

    /** One line crossed by a segment, at fraction {@code t} along it. */
    record Crossing(LineDef line, double t) {}

    /** Every line the segment (ax,ay)-(bx,by) crosses, nearest {@code a} first. */
    List<Crossing> crossings(double ax, double ay, double bx, double by) {
        List<Crossing> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int x0 = bucket(Math.min(ax, bx) - minX), x1 = bucket(Math.max(ax, bx) - minX);
        int y0 = bucket(Math.min(ay, by) - minY), y1 = bucket(Math.max(ay, by) - minY);
        for (int bx2 = x0; bx2 <= x1; bx2++) {
            for (int by2 = y0; by2 <= y1; by2++) {
                List<Integer> list = buckets.get(key(bx2, by2));
                if (list == null) continue;
                for (int idx : list) {
                    if (!seen.add(idx)) continue;
                    LineDef l = map.lines().get(idx);
                    Vertex p = map.vertices().get(l.v1()), q = map.vertices().get(l.v2());
                    if (!intersects(ax, ay, bx, by, p.x(), p.y(), q.x(), q.y())) continue;
                    double rx = bx - ax, ry = by - ay, sx = q.x() - p.x(), sy = q.y() - p.y();
                    double denom = rx * sy - ry * sx;
                    if (denom == 0) continue;
                    double t = ((p.x() - ax) * sy - (p.y() - ay) * sx) / denom;
                    out.add(new Crossing(l, t));
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(Crossing::t));
        return out;
    }

    private static boolean intersects(double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay), d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy), d4 = cross(ax, ay, bx, by, dx, dy);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0)) && d1 != 0 && d2 != 0;
    }

    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** Distance from a point to a line, for choosing which side of a wall gives way. */
    double distanceTo(LineDef line, double px, double py) {
        return distance(line, px, py);
    }

    private double distance(LineDef line, double px, double py) {
        Vertex a = map.vertices().get(line.v1()), b = map.vertices().get(line.v2());
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - a.x()) * dx + (py - a.y()) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double ex = a.x() + t * dx - px, ey = a.y() + t * dy - py;
        return Math.sqrt(ex * ex + ey * ey);
    }
}
