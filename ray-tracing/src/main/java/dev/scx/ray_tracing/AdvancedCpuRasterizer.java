package dev.scx.ray_tracing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Pure CPU software rasterizer intended as a learning comparison with a CPU ray tracer.
 * <p>
 * Features:
 * - triangle mesh spheres + ground
 * - perspective projection + perspective-correct interpolation
 * - Z buffer
 * - point light (Lambert + specular)
 * - point-light shadow cubemap (6 rasterized depth maps)
 * - reflection cubemap for the shiny sphere (6 rasterized color maps)
 * - planar reflection for the ground (extra mirrored-camera render every frame)
 * - WASD / mouse camera + FPS / pass counters
 * <p>
 * Deliberate simplifications:
 * - near-plane clipping is simplified: triangles crossing NEAR are discarded
 * - no mipmaps / textures / MSAA / TAA / PCF beyond a tiny shadow tap pattern
 * - reflection cubemap is rendered once because this demo scene is static
 * - mirror cubemap is one-bounce only; it does not recursively contain itself
 */
public class AdvancedCpuRasterizer extends JPanel {

    // =========================================================
    // Settings
    // =========================================================

    static final int WIDTH = 400;
    static final int HEIGHT = 300;
    static final int DISPLAY_SCALE = 2;

    static final int PLANAR_W = 240;
    static final int PLANAR_H = 180;

    static final int SHADOW_SIZE = 128;
    static final int REFLECTION_CUBE_SIZE = 112;

    static final int TILE = 32;
    static final boolean PARALLEL_MAIN = true;

    static final double FOV = Math.toRadians(70.0);
    static final double NEAR = 0.05;
    static final double EPS = 1e-5;
    static final double MOVE_SPEED = 3.0;
    static final double MOUSE_SENSITIVITY = 0.005;

    static final Vec3 SKY_BOTTOM = new Vec3(0.95, 0.97, 1.00);
    static final Vec3 SKY_TOP = new Vec3(0.20, 0.45, 0.85);

    // =========================================================
    // Buffers
    // =========================================================
    // Cubemap face bases. All use 90 degree FOV.
    static final Basis[] CUBE_BASES = new Basis[]{
        makeBasis(new Vec3(1, 0, 0), new Vec3(0, -1, 0)), // +X
        makeBasis(new Vec3(-1, 0, 0), new Vec3(0, -1, 0)), // -X
        makeBasis(new Vec3(0, 1, 0), new Vec3(0, 0, 1)), // +Y
        makeBasis(new Vec3(0, -1, 0), new Vec3(0, 0, -1)), // -Y
        makeBasis(new Vec3(0, 0, 1), new Vec3(0, -1, 0)), // +Z
        makeBasis(new Vec3(0, 0, -1), new Vec3(0, -1, 0))  // -Z
    };
    final double[] mainDepth = new double[WIDTH * HEIGHT];
    final BufferedImage planarImage = new BufferedImage(PLANAR_W, PLANAR_H, BufferedImage.TYPE_INT_RGB);
    final double[] planarDepth = new double[PLANAR_W * PLANAR_H];
    final CubeDepth shadowCube = new CubeDepth(SHADOW_SIZE);
    final CubeColor reflectionCube = new CubeColor(REFLECTION_CUBE_SIZE);
    final Set<Integer> keys = ConcurrentHashMap.newKeySet();

    // =========================================================
    // Input / camera
    // =========================================================
    final List<Triangle> scene = new ArrayList<>();
    final Material red = new Material(
        new Vec3(0.85, 0.12, 0.08), 0.75, 0.05, MaterialKind.NORMAL);
    final Material blue = new Material(
        new Vec3(0.08, 0.25, 0.85), 0.55, 0.04, MaterialKind.NORMAL);
    final Material mirror = new Material(
        new Vec3(0.75, 0.77, 0.80), 1.0, 0.82, MaterialKind.MIRROR);
    final Material ground = new Material(
        new Vec3(0.56, 0.56, 0.56), 0.12, 0.20, MaterialKind.GROUND);
    final Vec3 mirrorCenter = new Vec3(2.0, -0.15, -5.5);
    final PointLight light = new PointLight(
        new Vec3(-3, 5, 1),
        new Vec3(1.0, 0.95, 0.85),
        45.0
    );

    // =========================================================
    // Scene
    // =========================================================
    volatile BufferedImage front = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    BufferedImage back = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    volatile Vec3 cameraPosition = new Vec3(0, 0.2, 1.5);
    volatile double yaw = 0.0;
    volatile double pitch = 0.0;
    boolean rotating;
    int lastMouseX;

    // =========================================================
    // Performance
    // =========================================================
    int lastMouseY;
    volatile boolean running = true;
    volatile double fps;
    volatile double mainMs;
    volatile double planarMs;
    volatile int projectedTriangles;
    volatile long shadedPixels;

    // =========================================================
    // Math types
    // =========================================================
    volatile int sceneTriangles;

    public AdvancedCpuRasterizer() {
        setPreferredSize(new Dimension(WIDTH * DISPLAY_SCALE, HEIGHT * DISPLAY_SCALE));
        setFocusable(true);

        createScene();
        sceneTriangles = scene.size();

        // Static scene: these expensive auxiliary maps can be built once.
        long s0 = System.nanoTime();
        buildShadowCube();
        long s1 = System.nanoTime();
        buildReflectionCube();
        long s2 = System.nanoTime();

        System.out.printf("Shadow cubemap build: %.1f ms%n", (s1 - s0) / 1e6);
        System.out.printf("Reflection cubemap build: %.1f ms%n", (s2 - s1) / 1e6);
        System.out.println("Scene triangles: " + scene.size());

        installInput();

        Thread renderThread = new Thread(this::renderLoop, "Advanced-CPU-Rasterizer");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    static Basis makeBasis(Vec3 forward, Vec3 upHint) {
        Vec3 f = forward.normalize();
        Vec3 r = f.cross(upHint).normalize();
        Vec3 u = r.cross(f).normalize();
        return new Basis(f, r, u);
    }

    static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    static Vec3 gammaCorrect(Vec3 c) {
        return new Vec3(
            Math.sqrt(Math.max(0, c.x())),
            Math.sqrt(Math.max(0, c.y())),
            Math.sqrt(Math.max(0, c.z()))
        );
    }

    static int toRGB(Vec3 c) {
        int r = (int) (clamp(c.x(), 0, 1) * 255.0);
        int g = (int) (clamp(c.y(), 0, 1) * 255.0);
        int b = (int) (clamp(c.z(), 0, 1) * 255.0);
        return (r << 16) | (g << 8) | b;
    }

    static Vec3 fromRGB(int rgb) {
        // Offscreen reflection targets are stored after our simple sqrt gamma encode.
        // Decode them back to approximately linear space before mixing lighting.
        double r = ((rgb >> 16) & 255) / 255.0;
        double g = ((rgb >> 8) & 255) / 255.0;
        double b = (rgb & 255) / 255.0;
        return new Vec3(r * r, g * g, b * b);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("CPU Rasterizer - shadows + reflections");
            AdvancedCpuRasterizer panel = new AdvancedCpuRasterizer();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.requestFocusInWindow();
        });
    }

    void createScene() {
        addSphere(new Vec3(0, 0, -4), 1.0, 24, 48, red);
        addSphere(new Vec3(-2.0, -0.25, -5.0), 0.75, 20, 40, blue);
        addSphere(mirrorCenter, 0.85, 24, 48, mirror);

        // One giant ground quad -> two triangles.
        Vec3 n = new Vec3(0, 1, 0);
        double y = -1.0;
        double s = 30.0;
        Vertex a = new Vertex(new Vec3(-s, y, -s), n);
        Vertex b = new Vertex(new Vec3(s, y, -s), n);
        Vertex c = new Vertex(new Vec3(s, y, s), n);
        Vertex d = new Vertex(new Vec3(-s, y, s), n);
        scene.add(new Triangle(a, d, c, ground));
        scene.add(new Triangle(a, c, b, ground));
    }

    void addSphere(Vec3 center, double radius, int lat, int lon, Material material) {
        for (int y = 0; y < lat; y++) {
            double t0 = (double) y / lat * Math.PI;
            double t1 = (double) (y + 1) / lat * Math.PI;

            for (int x = 0; x < lon; x++) {
                double p0 = (double) x / lon * Math.PI * 2.0;
                double p1 = (double) (x + 1) / lon * Math.PI * 2.0;

                Vertex v00 = sphereVertex(center, radius, t0, p0);
                Vertex v10 = sphereVertex(center, radius, t0, p1);
                Vertex v01 = sphereVertex(center, radius, t1, p0);
                Vertex v11 = sphereVertex(center, radius, t1, p1);

                scene.add(new Triangle(v00, v01, v11, material));
                scene.add(new Triangle(v00, v11, v10, material));
            }
        }
    }

    Vertex sphereVertex(Vec3 center, double radius, double theta, double phi) {
        double st = Math.sin(theta);
        Vec3 n = new Vec3(
            st * Math.cos(phi),
            Math.cos(theta),
            st * Math.sin(phi)
        ).normalize();
        return new Vertex(center.add(n.mul(radius)), n);
    }

    // =========================================================
    // Construction
    // =========================================================

    Basis cameraBasis() {
        double cp = Math.cos(pitch);
        Vec3 f = new Vec3(
            Math.sin(yaw) * cp,
            Math.sin(pitch),
            -Math.cos(yaw) * cp
        ).normalize();
        return makeBasis(f, new Vec3(0, 1, 0));
    }

    View mainView() {
        return new View(cameraPosition, cameraBasis(), FOV, WIDTH, HEIGHT);
    }

    View mirroredGroundView() {
        // Reflect camera and its orientation across plane y = -1.
        double planeY = -1.0;
        Vec3 p = cameraPosition;
        Vec3 mirroredPos = new Vec3(p.x(), 2.0 * planeY - p.y(), p.z());

        Basis b = cameraBasis();
        Vec3 f = new Vec3(b.forward().x(), -b.forward().y(), b.forward().z());
        Vec3 u = new Vec3(b.up().x(), -b.up().y(), b.up().z());
        Basis mirroredBasis = makeBasis(f, u);

        return new View(mirroredPos, mirroredBasis, FOV, PLANAR_W, PLANAR_H);
    }

    ProjectedVertex projectVertex(Vertex vertex, View view) {
        Vec3 rel = vertex.position().sub(view.position());
        double vx = rel.dot(view.basis().right());
        double vy = rel.dot(view.basis().up());
        double vz = rel.dot(view.basis().forward());

        if (vz <= NEAR) {
            return null;
        }

        double tanHalf = Math.tan(view.fov() * 0.5);
        double aspect = (double) view.width() / view.height();

        double ndcX = vx / (vz * tanHalf * aspect);
        double ndcY = vy / (vz * tanHalf);

        double sx = (ndcX * 0.5 + 0.5) * view.width();
        double sy = (0.5 - ndcY * 0.5) * view.height();
        double invZ = 1.0 / vz;

        return new ProjectedVertex(
            sx, sy, invZ,
            vertex.position().mul(invZ),
            vertex.normal().mul(invZ)
        );
    }

    // =========================================================
    // Camera
    // =========================================================

    ProjectedTriangle projectTriangle(Triangle tri, View view) {
        ProjectedVertex a = projectVertex(tri.a(), view);
        ProjectedVertex b = projectVertex(tri.b(), view);
        ProjectedVertex c = projectVertex(tri.c(), view);

        // Learning simplification: no near-plane polygon clipping.
        if (a == null || b == null || c == null) {
            return null;
        }

        double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
        if (Math.abs(area) < 1e-10) {
            return null;
        }

        int minX = clampInt((int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))), 0, view.width() - 1);
        int maxX = clampInt((int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))), 0, view.width() - 1);
        int minY = clampInt((int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))), 0, view.height() - 1);
        int maxY = clampInt((int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))), 0, view.height() - 1);

        if (minX > maxX || minY > maxY) {
            return null;
        }
        return new ProjectedTriangle(a, b, c, tri.material(), minX, maxX, minY, maxY, area);
    }

    void buildShadowCube() {
        for (int face = 0; face < 6; face++) {
            Arrays.fill(shadowCube.faces[face], Double.POSITIVE_INFINITY);
            View view = new View(
                light.position(), CUBE_BASES[face], Math.PI / 2.0,
                SHADOW_SIZE, SHADOW_SIZE
            );

            for (Triangle tri : scene) {
                ProjectedTriangle p = projectTriangle(tri, view);
                if (p != null) {
                    rasterDepthTriangle(p, shadowCube.faces[face], SHADOW_SIZE, SHADOW_SIZE);
                }
            }
        }
    }

    void rasterDepthTriangle(ProjectedTriangle t, double[] depth, int w, int h) {
        boolean positive = t.area > 0;

        for (int y = t.minY; y <= t.maxY; y++) {
            double py = y + 0.5;
            for (int x = t.minX; x <= t.maxX; x++) {
                double px = x + 0.5;
                double w0 = edge(t.b.x(), t.b.y(), t.c.x(), t.c.y(), px, py);
                double w1 = edge(t.c.x(), t.c.y(), t.a.x(), t.a.y(), px, py);
                double w2 = edge(t.a.x(), t.a.y(), t.b.x(), t.b.y(), px, py);

                if (positive) {
                    if (w0 < 0 || w1 < 0 || w2 < 0) {
                        continue;
                    }
                } else {
                    if (w0 > 0 || w1 > 0 || w2 > 0) {
                        continue;
                    }
                }

                double l0 = w0 / t.area;
                double l1 = w1 / t.area;
                double l2 = w2 / t.area;
                double invZ = l0 * t.a.invZ() + l1 * t.b.invZ() + l2 * t.c.invZ();
                if (invZ <= 0) {
                    continue;
                }
                double z = 1.0 / invZ;
                int i = y * w + x;
                if (z < depth[i]) {
                    depth[i] = z;
                }
            }
        }
    }

    // =========================================================
    // Projection helpers
    // =========================================================

    boolean inPointShadow(Vec3 world, Vec3 normal) {
        // Small normal bias to reduce self-shadowing.
        Vec3 p = world.add(normal.mul(0.008));
        Vec3 d = p.sub(light.position());
        double actualDistance = d.length();
        if (actualDistance < 1e-8) {
            return false;
        }

        CubeSample s = cubeProject(d, shadowCube.size);
        double stored = bilinearDepth(shadowCube.faces[s.face], shadowCube.size, s.u, s.v);

        // The cubemap stores forward-axis Z, not radial length. Convert current direction
        // to that same depth definition.
        Basis b = CUBE_BASES[s.face];
        double projectedDepth = d.dot(b.forward());

        double bias = 0.015 + actualDistance * 0.001;
        return projectedDepth > stored + bias;
    }

    void buildReflectionCube() {
        for (int face = 0; face < 6; face++) {
            int[] color = reflectionCube.faces[face];
            double[] depth = reflectionCube.depth[face];
            Arrays.fill(depth, Double.POSITIVE_INFINITY);

            fillSkyFace(color, reflectionCube.size, CUBE_BASES[face]);

            View view = new View(
                mirrorCenter, CUBE_BASES[face], Math.PI / 2.0,
                reflectionCube.size, reflectionCube.size
            );

            for (Triangle tri : scene) {
                // Do not draw the mirror sphere into its own environment map.
                if (tri.material().kind() == MaterialKind.MIRROR) {
                    continue;
                }

                ProjectedTriangle p = projectTriangle(tri, view);
                if (p != null) {
                    rasterColorTriangleSimple(
                        p, view, color, depth,
                        reflectionCube.size, reflectionCube.size,
                        false, false
                    );
                }
            }
        }
    }

    void fillSkyFace(int[] pixels, int size, Basis basis) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double nx = ((x + 0.5) / size) * 2.0 - 1.0;
                double ny = 1.0 - ((y + 0.5) / size) * 2.0;
                Vec3 dir = basis.forward()
                    .add(basis.right().mul(nx))
                    .add(basis.up().mul(ny))
                    .normalize();
                pixels[y * size + x] = toRGB(gammaCorrect(sky(dir)));
            }
        }
    }

    // =========================================================
    // Shadow cubemap (6 depth renders from the point light)
    // =========================================================

    void renderPlanarReflection() {
        int[] pixels = ((DataBufferInt) planarImage.getRaster().getDataBuffer()).getData();
        Arrays.fill(planarDepth, Double.POSITIVE_INFINITY);

        View view = mirroredGroundView();
        fillSkyView(pixels, view);

        for (Triangle tri : scene) {
            if (tri.material().kind() == MaterialKind.GROUND) {
                continue;
            }
            ProjectedTriangle p = projectTriangle(tri, view);
            if (p != null) {
                rasterColorTriangleSimple(p, view, pixels, planarDepth, PLANAR_W, PLANAR_H, true, false);
            }
        }
    }

    void fillSkyView(int[] pixels, View view) {
        double tanHalf = Math.tan(view.fov() * 0.5);
        double aspect = (double) view.width() / view.height();

        for (int y = 0; y < view.height(); y++) {
            double ny = 1.0 - 2.0 * ((y + 0.5) / view.height());
            for (int x = 0; x < view.width(); x++) {
                double nx = 2.0 * ((x + 0.5) / view.width()) - 1.0;
                Vec3 dir = view.basis().forward()
                    .add(view.basis().right().mul(nx * tanHalf * aspect))
                    .add(view.basis().up().mul(ny * tanHalf))
                    .normalize();
                pixels[y * view.width() + x] = toRGB(gammaCorrect(sky(dir)));
            }
        }
    }

    void rasterColorTriangleSimple(
        ProjectedTriangle t,
        View view,
        int[] pixels,
        double[] depth,
        int w,
        int h,
        boolean useShadows,
        boolean allowPlanar
    ) {
        boolean positive = t.area > 0;

        for (int y = t.minY; y <= t.maxY; y++) {
            double py = y + 0.5;
            for (int x = t.minX; x <= t.maxX; x++) {
                double px = x + 0.5;

                double w0 = edge(t.b.x(), t.b.y(), t.c.x(), t.c.y(), px, py);
                double w1 = edge(t.c.x(), t.c.y(), t.a.x(), t.a.y(), px, py);
                double w2 = edge(t.a.x(), t.a.y(), t.b.x(), t.b.y(), px, py);

                if (positive) {
                    if (w0 < 0 || w1 < 0 || w2 < 0) {
                        continue;
                    }
                } else {
                    if (w0 > 0 || w1 > 0 || w2 > 0) {
                        continue;
                    }
                }

                double l0 = w0 / t.area;
                double l1 = w1 / t.area;
                double l2 = w2 / t.area;
                double invZ = l0 * t.a.invZ() + l1 * t.b.invZ() + l2 * t.c.invZ();
                if (invZ <= 0) {
                    continue;
                }

                double viewZ = 1.0 / invZ;
                int i = y * w + x;
                if (viewZ >= depth[i]) {
                    continue;
                }

                Vec3 worldOverZ = t.a.worldOverZ().mul(l0)
                    .add(t.b.worldOverZ().mul(l1))
                    .add(t.c.worldOverZ().mul(l2));
                Vec3 normalOverZ = t.a.normalOverZ().mul(l0)
                    .add(t.b.normalOverZ().mul(l1))
                    .add(t.c.normalOverZ().mul(l2));

                Vec3 world = worldOverZ.mul(1.0 / invZ);
                Vec3 normal = normalOverZ.mul(1.0 / invZ).normalize();

                Vec3 color = shade(world, normal, t.material, view.position(), useShadows, allowPlanar);
                pixels[i] = toRGB(gammaCorrect(color));
                depth[i] = viewZ;
            }
        }
    }

    // =========================================================
    // Reflection cubemap for the shiny sphere
    // =========================================================

    @SuppressWarnings("unchecked")
    void renderMain() {
        int[] pixels = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
        View view = mainView();
        fillSkyView(pixels, view);
        Arrays.fill(mainDepth, Double.POSITIVE_INFINITY);

        List<ProjectedTriangle> projected = new ArrayList<>(scene.size());
        for (Triangle tri : scene) {
            ProjectedTriangle p = projectTriangle(tri, view);
            if (p != null) {
                projected.add(p);
            }
        }
        projectedTriangles = projected.size();

        int tilesX = (WIDTH + TILE - 1) / TILE;
        int tilesY = (HEIGHT + TILE - 1) / TILE;
        int tileCount = tilesX * tilesY;

        ArrayList<ProjectedTriangle>[] bins = new ArrayList[tileCount];
        for (int i = 0; i < tileCount; i++) {
            bins[i] = new ArrayList<>();
        }

        for (ProjectedTriangle t : projected) {
            int minTX = t.minX / TILE;
            int maxTX = t.maxX / TILE;
            int minTY = t.minY / TILE;
            int maxTY = t.maxY / TILE;

            for (int ty = minTY; ty <= maxTY; ty++) {
                for (int tx = minTX; tx <= maxTX; tx++) {
                    bins[ty * tilesX + tx].add(t);
                }
            }
        }

        long[] counts = new long[tileCount];
        IntStream stream = IntStream.range(0, tileCount);
        if (PARALLEL_MAIN) {
            stream = stream.parallel();
        }

        stream.forEach(tileIndex -> {
            int tx = tileIndex % tilesX;
            int ty = tileIndex / tilesX;
            int minX = tx * TILE;
            int maxX = Math.min(WIDTH - 1, minX + TILE - 1);
            int minY = ty * TILE;
            int maxY = Math.min(HEIGHT - 1, minY + TILE - 1);
            long count = 0;

            for (ProjectedTriangle t : bins[tileIndex]) {
                count += rasterMainTriangle(t, view, pixels, minX, maxX, minY, maxY);
            }
            counts[tileIndex] = count;
        });

        long total = 0;
        for (long c : counts) {
            total += c;
        }
        shadedPixels = total;

        BufferedImage temp = front;
        front = back;
        back = temp;
    }

    long rasterMainTriangle(
        ProjectedTriangle t,
        View view,
        int[] pixels,
        int clipMinX, int clipMaxX,
        int clipMinY, int clipMaxY
    ) {
        int minX = Math.max(t.minX, clipMinX);
        int maxX = Math.min(t.maxX, clipMaxX);
        int minY = Math.max(t.minY, clipMinY);
        int maxY = Math.min(t.maxY, clipMaxY);
        if (minX > maxX || minY > maxY) {
            return 0;
        }

        boolean positive = t.area > 0;
        long count = 0;

        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;

                double w0 = edge(t.b.x(), t.b.y(), t.c.x(), t.c.y(), px, py);
                double w1 = edge(t.c.x(), t.c.y(), t.a.x(), t.a.y(), px, py);
                double w2 = edge(t.a.x(), t.a.y(), t.b.x(), t.b.y(), px, py);

                if (positive) {
                    if (w0 < 0 || w1 < 0 || w2 < 0) {
                        continue;
                    }
                } else {
                    if (w0 > 0 || w1 > 0 || w2 > 0) {
                        continue;
                    }
                }

                double l0 = w0 / t.area;
                double l1 = w1 / t.area;
                double l2 = w2 / t.area;

                double invZ = l0 * t.a.invZ() + l1 * t.b.invZ() + l2 * t.c.invZ();
                if (invZ <= 0) {
                    continue;
                }
                double viewZ = 1.0 / invZ;

                int i = y * WIDTH + x;
                if (viewZ >= mainDepth[i]) {
                    continue;
                }

                Vec3 worldOverZ = t.a.worldOverZ().mul(l0)
                    .add(t.b.worldOverZ().mul(l1))
                    .add(t.c.worldOverZ().mul(l2));
                Vec3 normalOverZ = t.a.normalOverZ().mul(l0)
                    .add(t.b.normalOverZ().mul(l1))
                    .add(t.c.normalOverZ().mul(l2));

                Vec3 world = worldOverZ.mul(1.0 / invZ);
                Vec3 normal = normalOverZ.mul(1.0 / invZ).normalize();

                Vec3 color = shade(world, normal, t.material, view.position(), true, true);
                pixels[i] = toRGB(gammaCorrect(color));
                mainDepth[i] = viewZ;
                count++;
            }
        }
        return count;
    }

    // =========================================================
    // Planar reflection: mirrored-camera render every frame
    // =========================================================

    Vec3 shade(
        Vec3 world,
        Vec3 normal,
        Material material,
        Vec3 eye,
        boolean useShadows,
        boolean allowPlanar
    ) {
        Vec3 result = material.color().mul(0.045);

        Vec3 toLight = light.position().sub(world);
        double distance = toLight.length();
        Vec3 lightDir = toLight.mul(1.0 / Math.max(distance, 1e-8));

        boolean shadowed = useShadows && inPointShadow(world, normal);
        if (!shadowed) {
            double attenuation = light.intensity() / (distance * distance);
            double diffuse = Math.max(0, normal.dot(lightDir));

            result = result.add(
                material.color()
                    .mul(light.color())
                    .mul(diffuse * attenuation)
            );

            Vec3 viewDir = eye.sub(world).normalize();
            Vec3 half = lightDir.add(viewDir).normalize();
            double spec = Math.pow(Math.max(0, normal.dot(half)), 64.0);
            result = result.add(light.color().mul(spec * material.specular() * attenuation));
        }

        Vec3 viewIncoming = world.sub(eye).normalize();

        if (material.kind() == MaterialKind.MIRROR) {
            Vec3 r = Vec3.reflect(viewIncoming, normal).normalize();
            Vec3 env = sampleCubeColor(reflectionCube, r);
            double f = material.reflectivity();
            result = result.mul(1.0 - f).add(env.mul(f));
        }

        if (allowPlanar && material.kind() == MaterialKind.GROUND) {
            Vec3 reflected = samplePlanarReflection(world);
            double fresnel = Math.pow(1.0 - Math.max(0, normal.dot(eye.sub(world).normalize())), 5.0);
            double f = Math.min(0.35, material.reflectivity() + fresnel * 0.15);
            result = result.mul(1.0 - f).add(reflected.mul(f));
        }

        return result;
    }

    Vec3 samplePlanarReflection(Vec3 world) {
        View reflectedView = mirroredGroundView();
        Vec3 rel = world.sub(reflectedView.position());
        double vx = rel.dot(reflectedView.basis().right());
        double vy = rel.dot(reflectedView.basis().up());
        double vz = rel.dot(reflectedView.basis().forward());
        if (vz <= NEAR) {
            return sky(new Vec3(0, 1, 0));
        }

        double tanHalf = Math.tan(reflectedView.fov() * 0.5);
        double aspect = (double) PLANAR_W / PLANAR_H;
        double ndcX = vx / (vz * tanHalf * aspect);
        double ndcY = vy / (vz * tanHalf);
        double u = ndcX * 0.5 + 0.5;
        double v = 0.5 - ndcY * 0.5;

        int[] pixels = ((DataBufferInt) planarImage.getRaster().getDataBuffer()).getData();
        return sampleImage(pixels, PLANAR_W, PLANAR_H, u, v);
    }

    // =========================================================
    // Generic simpler offscreen color rasterizer
    // =========================================================

    CubeSample cubeProject(Vec3 direction, int size) {
        Vec3 d = direction.normalize();

        int bestFace = 0;
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            double z = d.dot(CUBE_BASES[i].forward());
            if (z > best) {
                best = z;
                bestFace = i;
            }
        }

        Basis b = CUBE_BASES[bestFace];
        double z = Math.max(1e-8, d.dot(b.forward()));
        double nx = d.dot(b.right()) / z;
        double ny = d.dot(b.up()) / z;
        double u = nx * 0.5 + 0.5;
        double v = 0.5 - ny * 0.5;
        return new CubeSample(bestFace, u, v);
    }

    // =========================================================
    // Main raster pass (tiled / parallel)
    // =========================================================

    Vec3 sampleCubeColor(CubeColor cube, Vec3 direction) {
        CubeSample s = cubeProject(direction, cube.size);
        return sampleImage(cube.faces[s.face], cube.size, cube.size, s.u, s.v);
    }

    double bilinearDepth(double[] image, int size, double u, double v) {
        u = clamp(u, 0, 1);
        v = clamp(v, 0, 1);
        double x = u * (size - 1);
        double y = v * (size - 1);
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(size - 1, x0 + 1);
        int y1 = Math.min(size - 1, y0 + 1);
        double tx = x - x0;
        double ty = y - y0;

        double a = image[y0 * size + x0];
        double b = image[y0 * size + x1];
        double c = image[y1 * size + x0];
        double d = image[y1 * size + x1];

        // Infinity at cube edges/background means "no blocker".
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c) || !Double.isFinite(d)) {
            return Double.POSITIVE_INFINITY;
        }

        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    // =========================================================
    // Shading
    // =========================================================

    Vec3 sampleImage(int[] image, int w, int h, double u, double v) {
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return new Vec3(0, 0, 0);
        }
        double x = u * (w - 1);
        double y = v * (h - 1);
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        double tx = x - x0;
        double ty = y - y0;

        Vec3 a = fromRGB(image[y0 * w + x0]);
        Vec3 b = fromRGB(image[y0 * w + x1]);
        Vec3 c = fromRGB(image[y1 * w + x0]);
        Vec3 d = fromRGB(image[y1 * w + x1]);
        return a.mul((1 - tx) * (1 - ty))
            .add(b.mul(tx * (1 - ty)))
            .add(c.mul((1 - tx) * ty))
            .add(d.mul(tx * ty));
    }

    Vec3 sky(Vec3 dir) {
        double t = 0.5 * (dir.normalize().y() + 1.0);
        return SKY_BOTTOM.mul(1.0 - t).add(SKY_TOP.mul(t));
    }

    // =========================================================
    // Cubemap sampling
    // =========================================================

    void updateCamera(double dt) {
        Basis b = cameraBasis();
        Vec3 move = new Vec3(0, 0, 0);
        if (keys.contains(KeyEvent.VK_W)) {
            move = move.add(b.forward());
        }
        if (keys.contains(KeyEvent.VK_S)) {
            move = move.sub(b.forward());
        }
        if (keys.contains(KeyEvent.VK_D)) {
            move = move.add(b.right());
        }
        if (keys.contains(KeyEvent.VK_A)) {
            move = move.sub(b.right());
        }
        if (keys.contains(KeyEvent.VK_SPACE)) {
            move = move.add(new Vec3(0, 1, 0));
        }
        if (keys.contains(KeyEvent.VK_SHIFT)) {
            move = move.sub(new Vec3(0, 1, 0));
        }

        if (move.length() > 0) {
            cameraPosition = cameraPosition.add(move.normalize().mul(MOVE_SPEED * dt));
        }
    }

    void renderLoop() {
        long previous = System.nanoTime();
        double smoothed = 0;

        while (running) {
            long now = System.nanoTime();
            double dt = Math.min(0.1, (now - previous) / 1e9);
            previous = now;
            updateCamera(dt);

            long p0 = System.nanoTime();
            renderPlanarReflection();
            long p1 = System.nanoTime();
            renderMain();
            long p2 = System.nanoTime();

            planarMs = (p1 - p0) / 1e6;
            mainMs = (p2 - p1) / 1e6;
            double frameSeconds = (p2 - p0) / 1e9;
            double current = 1.0 / Math.max(frameSeconds, 1e-9);
            smoothed = smoothed == 0 ? current : smoothed * 0.90 + current * 0.10;
            fps = smoothed;
            repaint();
        }
    }

    void installInput() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                keys.add(e.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                keys.remove(e.getKeyCode());
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e)) {
                    rotating = true;
                    lastMouseX = e.getX();
                    lastMouseY = e.getY();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    rotating = false;
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (!rotating) {
                    return;
                }
                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                yaw += dx * MOUSE_SENSITIVITY;
                pitch -= dy * MOUSE_SENSITIVITY;
                pitch = clamp(pitch, -1.5, 1.5);
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(front, 0, 0, getWidth(), getHeight(), null);

        g.setColor(new Color(0, 0, 0, 175));
        g.fillRoundRect(10, 10, 430, 150, 12, 12);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        g.drawString(String.format("FPS: %.1f", fps), 20, 32);
        g.drawString(String.format("Main pass: %.2f ms", mainMs), 20, 52);
        g.drawString(String.format("Planar reflection pass: %.2f ms", planarMs), 20, 72);
        g.drawString("Scene triangles: " + sceneTriangles, 20, 92);
        g.drawString("Projected triangles: " + projectedTriangles, 20, 112);
        g.drawString("Pixels passing Z: " + shadedPixels, 20, 132);
        g.drawString("WASD | Space/Shift | hold RMB + drag", 20, 152);
    }

    @Override
    public void removeNotify() {
        running = false;
        super.removeNotify();
    }

    // =========================================================
    // Sky / color
    // =========================================================

    enum MaterialKind {NORMAL, MIRROR, GROUND}

    record Vec3(double x, double y, double z) {
        static Vec3 reflect(Vec3 d, Vec3 n) {
            return d.sub(n.mul(2.0 * d.dot(n)));
        }

        Vec3 add(Vec3 v) {
            return new Vec3(x + v.x, y + v.y, z + v.z);
        }

        Vec3 sub(Vec3 v) {
            return new Vec3(x - v.x, y - v.y, z - v.z);
        }

        Vec3 mul(double s) {
            return new Vec3(x * s, y * s, z * s);
        }

        Vec3 mul(Vec3 v) {
            return new Vec3(x * v.x, y * v.y, z * v.z);
        }

        double dot(Vec3 v) {
            return x * v.x + y * v.y + z * v.z;
        }

        Vec3 cross(Vec3 v) {
            return new Vec3(
                y * v.z - z * v.y,
                z * v.x - x * v.z,
                x * v.y - y * v.x
            );
        }

        double length() {
            return Math.sqrt(dot(this));
        }

        Vec3 normalize() {
            double len = length();
            return len < 1e-12 ? this : mul(1.0 / len);
        }

        Vec3 negate() {
            return new Vec3(-x, -y, -z);
        }
    }

    record Basis(Vec3 forward, Vec3 right, Vec3 up) {}

    record Material(Vec3 color, double specular, double reflectivity, MaterialKind kind) {}

    record Vertex(Vec3 position, Vec3 normal) {}

    record Triangle(Vertex a, Vertex b, Vertex c, Material material) {}

    record PointLight(Vec3 position, Vec3 color, double intensity) {}

    // =========================================================
    // Movement / loop
    // =========================================================

    record ProjectedVertex(
        double x,
        double y,
        double invZ,
        Vec3 worldOverZ,
        Vec3 normalOverZ
    ) {}

    static class ProjectedTriangle {
        final ProjectedVertex a, b, c;
        final Material material;
        final int minX, maxX, minY, maxY;
        final double area;

        ProjectedTriangle(
            ProjectedVertex a,
            ProjectedVertex b,
            ProjectedVertex c,
            Material material,
            int minX, int maxX, int minY, int maxY,
            double area
        ) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.material = material;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.area = area;
        }
    }

    // =========================================================
    // Input
    // =========================================================

    record View(Vec3 position, Basis basis, double fov, int width, int height) {}

    // =========================================================
    // Swing
    // =========================================================

    static class CubeDepth {
        final int size;
        final double[][] faces;

        CubeDepth(int size) {
            this.size = size;
            this.faces = new double[6][size * size];
        }
    }

    static class CubeColor {
        final int size;
        final int[][] faces;
        final double[][] depth;

        CubeColor(int size) {
            this.size = size;
            this.faces = new int[6][size * size];
            this.depth = new double[6][size * size];
        }
    }

    record CubeSample(int face, double u, double v) {}
}
