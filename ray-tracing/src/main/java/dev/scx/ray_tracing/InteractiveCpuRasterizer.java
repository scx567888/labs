package dev.scx.ray_tracing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

public class InteractiveCpuRasterizer extends JPanel {

    // =========================================================
    // Render Settings
    // =========================================================

    static final int RENDER_WIDTH = 400;
    static final int RENDER_HEIGHT = 300;
    static final int DISPLAY_SCALE = 2;

    static final int TILE_SIZE = 32;
    static final boolean PARALLEL_TILES = true;

    static final double FOV = Math.toRadians(70.0);
    static final double NEAR = 0.05;

    static final double MOVE_SPEED = 3.0;
    static final double MOUSE_SENSITIVITY = 0.005;

    // =========================================================
    // Buffers
    // =========================================================

    volatile BufferedImage frontImage = new BufferedImage(
            RENDER_WIDTH,
            RENDER_HEIGHT,
            BufferedImage.TYPE_INT_RGB
    );

    BufferedImage backImage = new BufferedImage(
            RENDER_WIDTH,
            RENDER_HEIGHT,
            BufferedImage.TYPE_INT_RGB
    );

    final double[] depthBuffer = new double[RENDER_WIDTH * RENDER_HEIGHT];

    // =========================================================
    // Input / Camera
    // =========================================================

    final Set<Integer> keys = ConcurrentHashMap.newKeySet();

    volatile Vec3 cameraPosition = new Vec3(0, 0.2, 1.5);
    volatile double yaw = 0.0;
    volatile double pitch = 0.0;

    boolean rotating;
    int lastMouseX;
    int lastMouseY;

    // =========================================================
    // Scene
    // =========================================================

    final List<Triangle> sceneTriangles = new ArrayList<>();

    final PointLight light = new PointLight(
            new Vec3(-3, 5, 1),
            new Vec3(1.0, 0.95, 0.85),
            45.0
    );

    // =========================================================
    // Performance
    // =========================================================

    volatile double fps;
    volatile int visibleTriangles;
    volatile long shadedPixels;

    volatile boolean running = true;

    // =========================================================
    // Constructor
    // =========================================================

    public InteractiveCpuRasterizer() {
        setPreferredSize(new Dimension(
                RENDER_WIDTH * DISPLAY_SCALE,
                RENDER_HEIGHT * DISPLAY_SCALE
        ));
        setFocusable(true);

        createScene();
        installInput();

        Thread renderThread = new Thread(this::renderLoop, "CPU-Rasterizer");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    // =========================================================
    // Math
    // =========================================================

    record Vec3(double x, double y, double z) {

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
            if (len == 0) return this;
            return mul(1.0 / len);
        }

        Vec3 negate() {
            return new Vec3(-x, -y, -z);
        }
    }

    record CameraBasis(Vec3 forward, Vec3 right, Vec3 up) {}

    record Material(Vec3 color, double specular) {}

    record Vertex(Vec3 position, Vec3 normal) {}

    record Triangle(Vertex a, Vertex b, Vertex c, Material material) {}

    record PointLight(Vec3 position, Vec3 color, double intensity) {}

    // Projected vertex keeps values divided by Z so we can do
    // perspective-correct interpolation later.
    record ProjectedVertex(
            double x,
            double y,
            double invZ,
            Vec3 worldOverZ,
            Vec3 normalOverZ
    ) {}

    static class ProjectedTriangle {
        final ProjectedVertex a;
        final ProjectedVertex b;
        final ProjectedVertex c;
        final Material material;

        final int minX;
        final int maxX;
        final int minY;
        final int maxY;

        final double area;

        ProjectedTriangle(
                ProjectedVertex a,
                ProjectedVertex b,
                ProjectedVertex c,
                Material material,
                int minX,
                int maxX,
                int minY,
                int maxY,
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
    // Scene Creation
    // =========================================================

    void createScene() {

        Material red = new Material(
                new Vec3(0.85, 0.12, 0.08),
                0.8
        );

        Material blue = new Material(
                new Vec3(0.08, 0.25, 0.85),
                0.5
        );

        Material silver = new Material(
                new Vec3(0.75, 0.75, 0.78),
                1.0
        );

        Material ground = new Material(
                new Vec3(0.55, 0.55, 0.55),
                0.15
        );

        addSphere(new Vec3(0, 0, -4), 1.0, 20, 40, red);
        addSphere(new Vec3(-2.0, -0.25, -5.0), 0.75, 18, 36, blue);
        addSphere(new Vec3(2.0, -0.15, -5.5), 0.85, 18, 36, silver);

        addGroundGrid(-1.0, 24, 1.0, ground);
    }

    void addSphere(
            Vec3 center,
            double radius,
            int latitudeSegments,
            int longitudeSegments,
            Material material
    ) {
        for (int y = 0; y < latitudeSegments; y++) {
            double v0 = (double) y / latitudeSegments;
            double v1 = (double) (y + 1) / latitudeSegments;

            double theta0 = v0 * Math.PI;
            double theta1 = v1 * Math.PI;

            for (int x = 0; x < longitudeSegments; x++) {
                double u0 = (double) x / longitudeSegments;
                double u1 = (double) (x + 1) / longitudeSegments;

                double phi0 = u0 * Math.PI * 2.0;
                double phi1 = u1 * Math.PI * 2.0;

                Vertex p00 = sphereVertex(center, radius, theta0, phi0);
                Vertex p10 = sphereVertex(center, radius, theta0, phi1);
                Vertex p01 = sphereVertex(center, radius, theta1, phi0);
                Vertex p11 = sphereVertex(center, radius, theta1, phi1);

                sceneTriangles.add(new Triangle(p00, p01, p11, material));
                sceneTriangles.add(new Triangle(p00, p11, p10, material));
            }
        }
    }

    Vertex sphereVertex(Vec3 center, double radius, double theta, double phi) {
        double sinTheta = Math.sin(theta);

        Vec3 normal = new Vec3(
                sinTheta * Math.cos(phi),
                Math.cos(theta),
                sinTheta * Math.sin(phi)
        ).normalize();

        Vec3 position = center.add(normal.mul(radius));
        return new Vertex(position, normal);
    }

    void addGroundGrid(
            double y,
            int halfCells,
            double cellSize,
            Material material
    ) {
        Vec3 n = new Vec3(0, 1, 0);

        for (int gx = -halfCells; gx < halfCells; gx++) {
            for (int gz = -halfCells; gz < halfCells; gz++) {
                double x0 = gx * cellSize;
                double x1 = (gx + 1) * cellSize;
                double z0 = gz * cellSize;
                double z1 = (gz + 1) * cellSize;

                Vertex a = new Vertex(new Vec3(x0, y, z0), n);
                Vertex b = new Vertex(new Vec3(x1, y, z0), n);
                Vertex c = new Vertex(new Vec3(x1, y, z1), n);
                Vertex d = new Vertex(new Vec3(x0, y, z1), n);

                sceneTriangles.add(new Triangle(a, d, c, material));
                sceneTriangles.add(new Triangle(a, c, b, material));
            }
        }
    }

    // =========================================================
    // Camera
    // =========================================================

    CameraBasis cameraBasis() {
        double cosPitch = Math.cos(pitch);

        Vec3 forward = new Vec3(
                Math.sin(yaw) * cosPitch,
                Math.sin(pitch),
                -Math.cos(yaw) * cosPitch
        ).normalize();

        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 right = forward.cross(worldUp).normalize();
        Vec3 up = right.cross(forward).normalize();

        return new CameraBasis(forward, right, up);
    }

    // =========================================================
    // Projection
    // =========================================================

    ProjectedVertex projectVertex(
            Vertex vertex,
            Vec3 camera,
            CameraBasis basis
    ) {
        Vec3 rel = vertex.position().sub(camera);

        double viewX = rel.dot(basis.right());
        double viewY = rel.dot(basis.up());
        double viewZ = rel.dot(basis.forward());

        if (viewZ <= NEAR) {
            return null;
        }

        double tanHalfFov = Math.tan(FOV * 0.5);
        double aspect = (double) RENDER_WIDTH / RENDER_HEIGHT;

        double ndcX = viewX / (viewZ * tanHalfFov * aspect);
        double ndcY = viewY / (viewZ * tanHalfFov);

        double screenX = (ndcX * 0.5 + 0.5) * RENDER_WIDTH;
        double screenY = (0.5 - ndcY * 0.5) * RENDER_HEIGHT;

        double invZ = 1.0 / viewZ;

        return new ProjectedVertex(
                screenX,
                screenY,
                invZ,
                vertex.position().mul(invZ),
                vertex.normal().mul(invZ)
        );
    }

    ProjectedTriangle projectTriangle(
            Triangle triangle,
            Vec3 camera,
            CameraBasis basis
    ) {
        ProjectedVertex a = projectVertex(triangle.a(), camera, basis);
        ProjectedVertex b = projectVertex(triangle.b(), camera, basis);
        ProjectedVertex c = projectVertex(triangle.c(), camera, basis);

        // Simplification for this learning demo:
        // if a triangle crosses the near plane, discard it instead of clipping it.
        if (a == null || b == null || c == null) {
            return null;
        }

        double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());

        if (Math.abs(area) < 1e-8) {
            return null;
        }

        int minX = clampInt(
                (int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))),
                0,
                RENDER_WIDTH - 1
        );

        int maxX = clampInt(
                (int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))),
                0,
                RENDER_WIDTH - 1
        );

        int minY = clampInt(
                (int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))),
                0,
                RENDER_HEIGHT - 1
        );

        int maxY = clampInt(
                (int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))),
                0,
                RENDER_HEIGHT - 1
        );

        if (maxX < 0 || maxY < 0 || minX >= RENDER_WIDTH || minY >= RENDER_HEIGHT) {
            return null;
        }

        if (minX > maxX || minY > maxY) {
            return null;
        }

        return new ProjectedTriangle(
                a,
                b,
                c,
                triangle.material(),
                minX,
                maxX,
                minY,
                maxY,
                area
        );
    }

    // =========================================================
    // Rasterization
    // =========================================================

    static double edge(
            double ax,
            double ay,
            double bx,
            double by,
            double px,
            double py
    ) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    void rasterizeTriangle(
            ProjectedTriangle t,
            int clipMinX,
            int clipMaxX,
            int clipMinY,
            int clipMaxY,
            int[] pixels,
            long[] localCounter
    ) {
        int minX = Math.max(t.minX, clipMinX);
        int maxX = Math.min(t.maxX, clipMaxX);
        int minY = Math.max(t.minY, clipMinY);
        int maxY = Math.min(t.maxY, clipMaxY);

        if (minX > maxX || minY > maxY) {
            return;
        }

        boolean positiveArea = t.area > 0;

        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;

            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;

                double w0 = edge(
                        t.b.x(), t.b.y(),
                        t.c.x(), t.c.y(),
                        px, py
                );

                double w1 = edge(
                        t.c.x(), t.c.y(),
                        t.a.x(), t.a.y(),
                        px, py
                );

                double w2 = edge(
                        t.a.x(), t.a.y(),
                        t.b.x(), t.b.y(),
                        px, py
                );

                if (positiveArea) {
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                } else {
                    if (w0 > 0 || w1 > 0 || w2 > 0) continue;
                }

                // Screen-space barycentric coordinates.
                double l0 = w0 / t.area;
                double l1 = w1 / t.area;
                double l2 = w2 / t.area;

                // Perspective-correct interpolation.
                double invZ =
                        l0 * t.a.invZ()
                        + l1 * t.b.invZ()
                        + l2 * t.c.invZ();

                if (invZ <= 0) continue;

                double viewZ = 1.0 / invZ;
                int index = y * RENDER_WIDTH + x;

                if (viewZ >= depthBuffer[index]) {
                    continue;
                }

                Vec3 worldOverZ =
                        t.a.worldOverZ().mul(l0)
                        .add(t.b.worldOverZ().mul(l1))
                        .add(t.c.worldOverZ().mul(l2));

                Vec3 normalOverZ =
                        t.a.normalOverZ().mul(l0)
                        .add(t.b.normalOverZ().mul(l1))
                        .add(t.c.normalOverZ().mul(l2));

                Vec3 worldPosition = worldOverZ.mul(1.0 / invZ);
                Vec3 normal = normalOverZ.mul(1.0 / invZ).normalize();

                Vec3 color = shade(worldPosition, normal, t.material);

                depthBuffer[index] = viewZ;
                pixels[index] = toRGB(gammaCorrect(color));
                localCounter[0]++;
            }
        }
    }

    // =========================================================
    // Lighting
    // =========================================================

    Vec3 shade(
            Vec3 worldPosition,
            Vec3 normal,
            Material material
    ) {
        Vec3 result = material.color().mul(0.04);

        Vec3 toLight = light.position().sub(worldPosition);
        double lightDistance = toLight.length();
        Vec3 lightDirection = toLight.mul(1.0 / lightDistance);

        double attenuation = light.intensity() /
                (lightDistance * lightDistance);

        double diffuse = Math.max(0, normal.dot(lightDirection));

        result = result.add(
                material.color()
                        .mul(light.color())
                        .mul(diffuse * attenuation)
        );

        Vec3 viewDirection = cameraPosition.sub(worldPosition).normalize();
        Vec3 halfVector = lightDirection.add(viewDirection).normalize();

        double specular = Math.pow(
                Math.max(0, normal.dot(halfVector)),
                64
        );

        result = result.add(
                light.color().mul(
                        specular
                        * material.specular()
                        * attenuation
                )
        );

        return result;
    }

    // =========================================================
    // Frame Rendering
    // =========================================================

    @SuppressWarnings("unchecked")
    void renderFrame() {
        int[] pixels = ((DataBufferInt)
                backImage.getRaster().getDataBuffer()
        ).getData();

        Arrays.fill(pixels, 0x7FA8E8);
        Arrays.fill(depthBuffer, Double.POSITIVE_INFINITY);

        Vec3 camera = cameraPosition;
        CameraBasis basis = cameraBasis();

        List<ProjectedTriangle> projected = new ArrayList<>(sceneTriangles.size());

        for (Triangle triangle : sceneTriangles) {
            ProjectedTriangle p = projectTriangle(triangle, camera, basis);
            if (p != null) {
                projected.add(p);
            }
        }

        visibleTriangles = projected.size();

        int tilesX = (RENDER_WIDTH + TILE_SIZE - 1) / TILE_SIZE;
        int tilesY = (RENDER_HEIGHT + TILE_SIZE - 1) / TILE_SIZE;
        int tileCount = tilesX * tilesY;

        ArrayList<ProjectedTriangle>[] bins = new ArrayList[tileCount];
        for (int i = 0; i < tileCount; i++) {
            bins[i] = new ArrayList<>();
        }

        for (ProjectedTriangle t : projected) {
            int minTileX = t.minX / TILE_SIZE;
            int maxTileX = t.maxX / TILE_SIZE;
            int minTileY = t.minY / TILE_SIZE;
            int maxTileY = t.maxY / TILE_SIZE;

            for (int ty = minTileY; ty <= maxTileY; ty++) {
                for (int tx = minTileX; tx <= maxTileX; tx++) {
                    bins[ty * tilesX + tx].add(t);
                }
            }
        }

        long[] counters = new long[tileCount];

        IntStream tileStream = IntStream.range(0, tileCount);
        if (PARALLEL_TILES) {
            tileStream = tileStream.parallel();
        }

        tileStream.forEach(tileIndex -> {
            int tx = tileIndex % tilesX;
            int ty = tileIndex / tilesX;

            int minX = tx * TILE_SIZE;
            int maxX = Math.min(RENDER_WIDTH - 1, minX + TILE_SIZE - 1);
            int minY = ty * TILE_SIZE;
            int maxY = Math.min(RENDER_HEIGHT - 1, minY + TILE_SIZE - 1);

            long[] localCounter = new long[1];

            for (ProjectedTriangle t : bins[tileIndex]) {
                rasterizeTriangle(
                        t,
                        minX,
                        maxX,
                        minY,
                        maxY,
                        pixels,
                        localCounter
                );
            }

            counters[tileIndex] = localCounter[0];
        });

        long sum = 0;
        for (long c : counters) sum += c;
        shadedPixels = sum;

        BufferedImage temp = frontImage;
        frontImage = backImage;
        backImage = temp;
    }

    // =========================================================
    // Camera Movement
    // =========================================================

    void updateCamera(double deltaTime) {
        CameraBasis basis = cameraBasis();
        Vec3 movement = new Vec3(0, 0, 0);

        if (keys.contains(KeyEvent.VK_W)) movement = movement.add(basis.forward());
        if (keys.contains(KeyEvent.VK_S)) movement = movement.sub(basis.forward());
        if (keys.contains(KeyEvent.VK_D)) movement = movement.add(basis.right());
        if (keys.contains(KeyEvent.VK_A)) movement = movement.sub(basis.right());
        if (keys.contains(KeyEvent.VK_SPACE)) movement = movement.add(new Vec3(0, 1, 0));
        if (keys.contains(KeyEvent.VK_SHIFT)) movement = movement.sub(new Vec3(0, 1, 0));

        if (movement.length() > 0) {
            cameraPosition = cameraPosition.add(
                    movement.normalize().mul(MOVE_SPEED * deltaTime)
            );
        }
    }

    // =========================================================
    // Loop
    // =========================================================

    void renderLoop() {
        long previous = System.nanoTime();
        double smoothFps = 0;

        while (running) {
            long now = System.nanoTime();
            double dt = (now - previous) / 1_000_000_000.0;
            previous = now;
            dt = Math.min(dt, 0.1);

            updateCamera(dt);

            long start = System.nanoTime();
            renderFrame();
            long end = System.nanoTime();

            double seconds = (end - start) / 1_000_000_000.0;
            double currentFps = 1.0 / Math.max(seconds, 1e-9);

            if (smoothFps == 0) smoothFps = currentFps;
            else smoothFps = smoothFps * 0.9 + currentFps * 0.1;

            fps = smoothFps;
            repaint();
        }
    }

    // =========================================================
    // Input
    // =========================================================

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
                if (!rotating) return;

                int dx = e.getX() - lastMouseX;
                int dy = e.getY() - lastMouseY;

                lastMouseX = e.getX();
                lastMouseY = e.getY();

                yaw += dx * MOUSE_SENSITIVITY;
                pitch -= dy * MOUSE_SENSITIVITY;

                pitch = Math.max(-1.5, Math.min(1.5, pitch));
            }
        });
    }

    // =========================================================
    // Color
    // =========================================================

    static Vec3 gammaCorrect(Vec3 c) {
        return new Vec3(
                Math.sqrt(Math.max(0, c.x())),
                Math.sqrt(Math.max(0, c.y())),
                Math.sqrt(Math.max(0, c.z()))
        );
    }

    static int toRGB(Vec3 c) {
        int r = (int) (clamp(c.x()) * 255);
        int g = (int) (clamp(c.y()) * 255);
        int b = (int) (clamp(c.z()) * 255);
        return (r << 16) | (g << 8) | b;
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // =========================================================
    // Swing
    // =========================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        g.drawImage(
                frontImage,
                0,
                0,
                getWidth(),
                getHeight(),
                null
        );

        g.setColor(new Color(0, 0, 0, 165));
        g.fillRoundRect(10, 10, 370, 110, 12, 12);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        g.drawString(String.format("FPS: %.1f", fps), 20, 32);
        g.drawString("Scene triangles: " + sceneTriangles.size(), 20, 52);
        g.drawString("Projected triangles: " + visibleTriangles, 20, 72);
        g.drawString("Pixels passing depth: " + shadedPixels, 20, 92);
        g.drawString("WASD | Space/Shift | Hold RMB + drag", 20, 112);
    }

    @Override
    public void removeNotify() {
        running = false;
        super.removeNotify();
    }

    // =========================================================
    // Main
    // =========================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Pure CPU Interactive Rasterizer");
            InteractiveCpuRasterizer panel = new InteractiveCpuRasterizer();

            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            panel.requestFocusInWindow();
        });
    }
}
