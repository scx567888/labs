package dev.scx.ray_tracing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public class InteractiveCpuRayTracer extends JPanel {

    // =========================================================
    // Render Settings
    // =========================================================

    static final int RENDER_WIDTH = 960;
    static final int RENDER_HEIGHT = 540;

    // 显示时放大 2 倍
    static final int DISPLAY_SCALE = 1;

    // 每像素采样数
    static final int SAMPLES = 1;

    // 最大反射次数
    static final int MAX_BOUNCES = 3;

    // true = CPU 多核并行
    static final boolean PARALLEL = true;

    static final double EPSILON = 0.0001;

    static final double MOVE_SPEED = 3.0;

    static final double MOUSE_SENSITIVITY = 0.005;

    // =========================================================
    // Frame Buffer
    // =========================================================

    volatile BufferedImage frontImage =
        new BufferedImage(
            RENDER_WIDTH,
            RENDER_HEIGHT,
            BufferedImage.TYPE_INT_RGB
        );

    BufferedImage backImage =
        new BufferedImage(
            RENDER_WIDTH,
            RENDER_HEIGHT,
            BufferedImage.TYPE_INT_RGB
        );

    // =========================================================
    // Scene
    // =========================================================

    final Scene scene = new Scene();

    // =========================================================
    // Input
    // =========================================================

    final Set<Integer> keys =
        ConcurrentHashMap.newKeySet();

    // =========================================================
    // Camera
    // =========================================================

    volatile Vec3 cameraPosition =
        new Vec3(
            0,
            0.2,
            1.5
        );

    volatile double yaw = 0.0;
    volatile double pitch = 0.0;

    // =========================================================
    // Performance
    // =========================================================

    volatile double fps = 0.0;

    volatile double primaryMRaysPerSecond = 0.0;

    private volatile boolean running = true;

    private boolean rotating = false;

    private int lastMouseX;
    private int lastMouseY;

    // =========================================================
    // Constructor
    // =========================================================

    public InteractiveCpuRayTracer() {

        setPreferredSize(
            new Dimension(
                RENDER_WIDTH * DISPLAY_SCALE,
                RENDER_HEIGHT * DISPLAY_SCALE
            )
        );

        setFocusable(true);

        createScene();

        installInput();

        Thread renderThread =
            new Thread(
                this::renderLoop,
                "CPU-RayTracer"
            );

        renderThread.setDaemon(true);

        renderThread.start();
    }

    // =========================================================
    // Vec3
    // =========================================================

    record Vec3(
        double x,
        double y,
        double z
    ) {

        Vec3 add(Vec3 v) {

            return new Vec3(
                x + v.x,
                y + v.y,
                z + v.z
            );
        }

        Vec3 sub(Vec3 v) {

            return new Vec3(
                x - v.x,
                y - v.y,
                z - v.z
            );
        }

        Vec3 mul(double s) {

            return new Vec3(
                x * s,
                y * s,
                z * s
            );
        }

        Vec3 mul(Vec3 v) {

            return new Vec3(
                x * v.x,
                y * v.y,
                z * v.z
            );
        }

        double dot(Vec3 v) {

            return
                x * v.x +
                    y * v.y +
                    z * v.z;
        }

        Vec3 cross(Vec3 v) {

            return new Vec3(
                y * v.z - z * v.y,
                z * v.x - x * v.z,
                x * v.y - y * v.x
            );
        }

        double length() {

            return Math.sqrt(
                dot(this)
            );
        }

        Vec3 normalize() {

            double len = length();

            if (len == 0)
                return this;

            return mul(
                1.0 / len
            );
        }

        Vec3 negate() {

            return new Vec3(
                -x,
                -y,
                -z
            );
        }

        static Vec3 reflect(
            Vec3 direction,
            Vec3 normal
        ) {

            return direction.sub(
                normal.mul(
                    2.0 *
                        direction.dot(normal)
                )
            );
        }
    }

    // =========================================================
    // Ray
    // =========================================================

    record Ray(
        Vec3 origin,
        Vec3 direction
    ) {

        Ray {

            direction =
                direction.normalize();
        }

        Vec3 at(double t) {

            return origin.add(
                direction.mul(t)
            );
        }
    }

    // =========================================================
    // Material
    // =========================================================

    record Material(
        Vec3 color,

        // 0 ~ 1
        double reflectivity,

        // 镜面高光
        double specular
    ) {
    }

    // =========================================================
    // Hit
    // =========================================================

    record Hit(
        double distance,
        Vec3 position,
        Vec3 normal,
        Material material
    ) {
    }

    // =========================================================
    // Scene Object
    // =========================================================

    interface SceneObject {

        Hit intersect(
            Ray ray
        );
    }

    // =========================================================
    // Sphere
    // =========================================================

    record Sphere(
        Vec3 center,
        double radius,
        Material material
    ) implements SceneObject {

        @Override
        public Hit intersect(
            Ray ray
        ) {

            Vec3 oc =
                ray.origin()
                    .sub(center);

            double a =
                ray.direction()
                    .dot(
                        ray.direction()
                    );

            // 因为 b = 2 * ...
            // 这里直接用 halfB
            double halfB =
                oc.dot(
                    ray.direction()
                );

            double c =
                oc.dot(oc)
                    -
                    radius * radius;

            double discriminant =
                halfB * halfB
                    -
                    a * c;

            if (discriminant < 0)
                return null;

            double sqrt =
                Math.sqrt(
                    discriminant
                );

            // 最近交点
            double t =
                (
                    -halfB - sqrt
                )
                    /
                    a;

            if (t <= EPSILON) {

                t =
                    (
                        -halfB + sqrt
                    )
                        /
                        a;

                if (t <= EPSILON)
                    return null;
            }

            Vec3 position =
                ray.at(t);

            Vec3 normal =
                position
                    .sub(center)
                    .normalize();

            return new Hit(
                t,
                position,
                normal,
                material
            );
        }
    }

    // =========================================================
    // Plane
    // =========================================================

    record Plane(
        Vec3 point,
        Vec3 normal,
        Material material
    ) implements SceneObject {

        @Override
        public Hit intersect(
            Ray ray
        ) {

            double denominator =
                normal.dot(
                    ray.direction()
                );

            if (
                Math.abs(
                    denominator
                ) < 1e-8
            ) {
                return null;
            }

            double t =
                point
                    .sub(
                        ray.origin()
                    )
                    .dot(normal)
                    /
                    denominator;

            if (t <= EPSILON)
                return null;

            // 确保 normal 朝向 ray
            Vec3 n =
                denominator < 0
                    ?
                    normal
                    :
                    normal.negate();

            return new Hit(
                t,
                ray.at(t),
                n,
                material
            );
        }
    }

    // =========================================================
    // Light
    // =========================================================

    record PointLight(
        Vec3 position,
        Vec3 color,
        double intensity
    ) {
    }

    // =========================================================
    // Scene
    // =========================================================

    static class Scene {

        List<SceneObject> objects;

        PointLight light;

        Hit intersect(
            Ray ray
        ) {

            Hit closest = null;

            double closestDistance =
                Double.POSITIVE_INFINITY;

            for (
                SceneObject object :
                objects
            ) {

                Hit hit =
                    object.intersect(
                        ray
                    );

                if (
                    hit != null &&
                        hit.distance()
                            <
                            closestDistance
                ) {

                    closest = hit;

                    closestDistance =
                        hit.distance();
                }
            }

            return closest;
        }
    }

    // =========================================================
    // Scene Setup
    // =========================================================

    void createScene() {

        Material red =
            new Material(
                new Vec3(
                    0.85,
                    0.12,
                    0.08
                ),
                0.20,
                0.8
            );

        Material blue =
            new Material(
                new Vec3(
                    0.08,
                    0.25,
                    0.85
                ),
                0.10,
                0.5
            );

        Material mirror =
            new Material(
                new Vec3(
                    0.85,
                    0.85,
                    0.85
                ),
                0.80,
                1.0
            );

        Material ground =
            new Material(
                new Vec3(
                    0.55,
                    0.55,
                    0.55
                ),
                0.10,
                0.15
            );

        scene.objects =
            List.of(

                // 中间红球
                new Sphere(
                    new Vec3(
                        0,
                        0,
                        -4
                    ),
                    1.0,
                    red
                ),

                // 左边蓝球
                new Sphere(
                    new Vec3(
                        -2.0,
                        -0.25,
                        -5.0
                    ),
                    0.75,
                    blue
                ),

                // 右边镜面球
                new Sphere(
                    new Vec3(
                        2.0,
                        -0.15,
                        -5.5
                    ),
                    0.85,
                    mirror
                ),

                // 地面
                new Plane(
                    new Vec3(
                        0,
                        -1,
                        0
                    ),

                    new Vec3(
                        0,
                        1,
                        0
                    ),

                    ground
                )
            );

        scene.light =
            new PointLight(

                new Vec3(
                    -3,
                    5,
                    1
                ),

                new Vec3(
                    1.0,
                    0.95,
                    0.85
                ),

                45.0
            );
    }

    // =========================================================
    // Ray Tracing
    // =========================================================

    Vec3 trace(
        Ray ray,
        int depth
    ) {

        if (
            depth >= MAX_BOUNCES
        ) {
            return new Vec3(
                0,
                0,
                0
            );
        }

        Hit hit =
            scene.intersect(
                ray
            );

        // 没撞东西
        if (hit == null) {

            return sky(
                ray
            );
        }

        Material material =
            hit.material();

        // 少量环境光
        Vec3 result =
            material
                .color()
                .mul(
                    0.04
                );

        // =====================================================
        // Point Light
        // =====================================================

        Vec3 toLight =
            scene
                .light
                .position()
                .sub(
                    hit.position()
                );

        double lightDistance =
            toLight.length();

        Vec3 lightDirection =
            toLight.mul(
                1.0 /
                    lightDistance
            );

        // =====================================================
        // Shadow Ray
        // =====================================================

        Ray shadowRay =
            new Ray(

                hit.position()
                    .add(
                        hit.normal()
                            .mul(
                                EPSILON
                            )
                    ),

                lightDirection
            );

        Hit blocker =
            scene.intersect(
                shadowRay
            );

        boolean inShadow =
            blocker != null &&
                blocker.distance()
                    <
                    lightDistance;

        // =====================================================
        // Lighting
        // =====================================================

        if (!inShadow) {

            // 距离平方衰减
            double attenuation =
                scene
                    .light
                    .intensity()
                    /
                    (
                        lightDistance
                            *
                            lightDistance
                    );

            // Lambert
            double diffuse =
                Math.max(
                    0,

                    hit.normal()
                        .dot(
                            lightDirection
                        )
                );

            Vec3 diffuseColor =
                material
                    .color()
                    .mul(
                        scene
                            .light
                            .color()
                    )
                    .mul(
                        diffuse
                            *
                            attenuation
                    );

            result =
                result.add(
                    diffuseColor
                );

            // =================================================
            // Specular
            // =================================================

            Vec3 viewDirection =
                ray
                    .direction()
                    .negate();

            Vec3 reflectedLight =
                Vec3.reflect(

                    lightDirection.negate(),

                    hit.normal()
                );

            double spec =
                Math.pow(

                    Math.max(
                        0,

                        viewDirection
                            .dot(
                                reflectedLight
                            )
                    ),

                    64
                );

            Vec3 specular =
                scene
                    .light
                    .color()
                    .mul(
                        spec
                            *
                            material
                                .specular()
                            *
                            attenuation
                    );

            result =
                result.add(
                    specular
                );
        }

        // =====================================================
        // Reflection
        // =====================================================

        if (
            material.reflectivity() > 0
                &&
                depth + 1
                    <
                    MAX_BOUNCES
        ) {

            Vec3 reflectionDirection =
                Vec3.reflect(

                    ray.direction(),

                    hit.normal()
                );

            Ray reflectionRay =
                new Ray(

                    hit.position()
                        .add(
                            hit.normal()
                                .mul(
                                    EPSILON
                                )
                        ),

                    reflectionDirection
                );

            // 注意这里
            //
            // 递归！
            //
            Vec3 reflection =
                trace(
                    reflectionRay,
                    depth + 1
                );

            double reflectivity =
                material.reflectivity();

            result =
                result
                    .mul(
                        1.0
                            -
                            reflectivity
                    )
                    .add(
                        reflection.mul(
                            reflectivity
                        )
                    );
        }

        return result;
    }

    // =========================================================
    // Sky
    // =========================================================

    Vec3 sky(
        Ray ray
    ) {

        double t =
            0.5
                *
                (
                    ray.direction().y()
                        +
                        1.0
                );

        Vec3 bottom =
            new Vec3(
                0.95,
                0.97,
                1.0
            );

        Vec3 top =
            new Vec3(
                0.20,
                0.45,
                0.85
            );

        return bottom
            .mul(
                1.0 - t
            )
            .add(
                top.mul(t)
            );
    }

    // =========================================================
    // Camera
    // =========================================================

    record CameraBasis(
        Vec3 forward,
        Vec3 right,
        Vec3 up
    ) {
    }

    CameraBasis cameraBasis() {

        double cosPitch =
            Math.cos(
                pitch
            );

        Vec3 forward =
            new Vec3(

                Math.sin(yaw)
                    *
                    cosPitch,

                Math.sin(pitch),

                -Math.cos(yaw)
                    *
                    cosPitch

            ).normalize();

        Vec3 worldUp =
            new Vec3(
                0,
                1,
                0
            );

        Vec3 right =
            forward
                .cross(
                    worldUp
                )
                .normalize();

        Vec3 up =
            right
                .cross(
                    forward
                )
                .normalize();

        return new CameraBasis(
            forward,
            right,
            up
        );
    }

    Ray cameraRay(
        double pixelX,
        double pixelY,
        Vec3 origin,
        CameraBasis basis
    ) {

        double aspect =
            (double)
                RENDER_WIDTH
                /
                RENDER_HEIGHT;

        // 70° FOV
        double fov =
            Math.toRadians(
                70
            );

        double halfHeight =
            Math.tan(
                fov * 0.5
            );

        double halfWidth =
            aspect
                *
                halfHeight;

        // Pixel -> NDC
        double x =
            (
                2.0
                    *
                    (
                        pixelX
                            /
                            RENDER_WIDTH
                    )
                    -
                    1.0
            )
                *
                halfWidth;

        double y =
            (
                1.0
                    -
                    2.0
                        *
                        (
                            pixelY
                                /
                                RENDER_HEIGHT
                        )
            )
                *
                halfHeight;

        Vec3 direction =
            basis
                .forward()
                .add(
                    basis.right()
                        .mul(x)
                )
                .add(
                    basis.up()
                        .mul(y)
                );

        return new Ray(
            origin,
            direction
        );
    }

    // =========================================================
    // Render Frame
    // =========================================================

    void renderFrame() {

        int[] pixels =
            (
                (DataBufferInt)
                    backImage
                        .getRaster()
                        .getDataBuffer()
            )
                .getData();

        // 当前这一帧 Camera 固定下来
        Vec3 camera =
            cameraPosition;

        CameraBasis basis =
            cameraBasis();

        IntStream rows =
            IntStream.range(
                0,
                RENDER_HEIGHT
            );

        if (PARALLEL) {

            rows =
                rows.parallel();
        }

        rows.forEach(
            y -> {

                ThreadLocalRandom random =
                    ThreadLocalRandom.current();

                for (
                    int x = 0;
                    x < RENDER_WIDTH;
                    x++
                ) {

                    Vec3 color =
                        new Vec3(
                            0,
                            0,
                            0
                        );

                    for (
                        int sample = 0;
                        sample < SAMPLES;
                        sample++
                    ) {

                        double offsetX =
                            SAMPLES == 1
                                ?
                                0.5
                                :
                                random.nextDouble();

                        double offsetY =
                            SAMPLES == 1
                                ?
                                0.5
                                :
                                random.nextDouble();

                        Ray ray =
                            cameraRay(

                                x + offsetX,

                                y + offsetY,

                                camera,

                                basis
                            );

                        color =
                            color.add(

                                trace(
                                    ray,
                                    0
                                )
                            );
                    }

                    color =
                        color.mul(
                            1.0 /
                                SAMPLES
                        );

                    color =
                        gammaCorrect(
                            color
                        );

                    pixels[
                        y * RENDER_WIDTH
                            +
                            x
                        ] =
                        toRGB(
                            color
                        );
                }
            }
        );

        // =====================================================
        // Double Buffer swap
        // =====================================================

        BufferedImage temp =
            frontImage;

        frontImage =
            backImage;

        backImage =
            temp;
    }

    // =========================================================
    // Camera Movement
    // =========================================================

    void updateCamera(
        double deltaTime
    ) {

        CameraBasis basis =
            cameraBasis();

        Vec3 movement =
            new Vec3(
                0,
                0,
                0
            );

        if (
            keys.contains(
                KeyEvent.VK_W
            )
        ) {
            movement =
                movement.add(
                    basis.forward()
                );
        }

        if (
            keys.contains(
                KeyEvent.VK_S
            )
        ) {
            movement =
                movement.sub(
                    basis.forward()
                );
        }

        if (
            keys.contains(
                KeyEvent.VK_D
            )
        ) {
            movement =
                movement.add(
                    basis.right()
                );
        }

        if (
            keys.contains(
                KeyEvent.VK_A
            )
        ) {
            movement =
                movement.sub(
                    basis.right()
                );
        }

        if (
            keys.contains(
                KeyEvent.VK_SPACE
            )
        ) {
            movement =
                movement.add(
                    new Vec3(
                        0,
                        1,
                        0
                    )
                );
        }

        if (
            keys.contains(
                KeyEvent.VK_SHIFT
            )
        ) {
            movement =
                movement.sub(
                    new Vec3(
                        0,
                        1,
                        0
                    )
                );
        }

        if (
            movement.length() > 0
        ) {

            movement =
                movement
                    .normalize()
                    .mul(
                        MOVE_SPEED
                            *
                            deltaTime
                    );

            cameraPosition =
                cameraPosition
                    .add(
                        movement
                    );
        }
    }

    // =========================================================
    // Main Render Loop
    // =========================================================

    void renderLoop() {

        long previous =
            System.nanoTime();

        double smoothFPS =
            0;

        while (running) {

            long now =
                System.nanoTime();

            double deltaTime =
                (
                    now - previous
                )
                    /
                    1_000_000_000.0;

            previous =
                now;

            // 防止窗口拖动等导致 dt 爆炸
            deltaTime =
                Math.min(
                    deltaTime,
                    0.1
                );

            // 更新相机
            updateCamera(
                deltaTime
            );

            // ================================================
            // Benchmark Render
            // ================================================

            long start =
                System.nanoTime();

            renderFrame();

            long end =
                System.nanoTime();

            double renderSeconds =
                (
                    end - start
                )
                    /
                    1_000_000_000.0;

            double currentFPS =
                1.0
                    /
                    renderSeconds;

            // 做一点平滑
            if (
                smoothFPS == 0
            ) {

                smoothFPS =
                    currentFPS;

            } else {

                smoothFPS =
                    smoothFPS * 0.9
                        +
                        currentFPS * 0.1;
            }

            fps =
                smoothFPS;

            // Primary Rays/s
            //
            // 注意：
            // 这里只统计 Camera 发出的 ray
            //
            // 没把 Shadow / Reflection Ray 算进去
            primaryMRaysPerSecond =
                (
                    RENDER_WIDTH
                        *
                        (double)
                            RENDER_HEIGHT
                        *
                        SAMPLES
                        *
                        fps
                )
                    /
                    1_000_000.0;

            repaint();
        }
    }

    // =========================================================
    // Input
    // =========================================================

    void installInput() {

        // Keyboard
        addKeyListener(
            new KeyAdapter() {

                @Override
                public void keyPressed(
                    KeyEvent e
                ) {

                    keys.add(
                        e.getKeyCode()
                    );
                }

                @Override
                public void keyReleased(
                    KeyEvent e
                ) {

                    keys.remove(
                        e.getKeyCode()
                    );
                }
            }
        );

        // Mouse Button
        addMouseListener(
            new MouseAdapter() {

                @Override
                public void mousePressed(
                    MouseEvent e
                ) {

                    requestFocusInWindow();

                    if (
                        SwingUtilities
                            .isRightMouseButton(
                                e
                            )
                    ) {

                        rotating = true;

                        lastMouseX =
                            e.getX();

                        lastMouseY =
                            e.getY();
                    }
                }

                @Override
                public void mouseReleased(
                    MouseEvent e
                ) {

                    if (
                        SwingUtilities
                            .isRightMouseButton(
                                e
                            )
                    ) {

                        rotating = false;
                    }
                }
            }
        );

        // Mouse Look
        addMouseMotionListener(
            new MouseMotionAdapter() {

                @Override
                public void mouseDragged(
                    MouseEvent e
                ) {

                    if (!rotating)
                        return;

                    int deltaX =
                        e.getX()
                            -
                            lastMouseX;

                    int deltaY =
                        e.getY()
                            -
                            lastMouseY;

                    lastMouseX =
                        e.getX();

                    lastMouseY =
                        e.getY();

                    yaw +=
                        deltaX
                            *
                            MOUSE_SENSITIVITY;

                    pitch -=
                        deltaY
                            *
                            MOUSE_SENSITIVITY;

                    // 防止翻转
                    pitch =
                        Math.max(
                            -1.5,

                            Math.min(
                                1.5,
                                pitch
                            )
                        );
                }
            }
        );
    }

    // =========================================================
    // Gamma
    // =========================================================

    static Vec3 gammaCorrect(
        Vec3 color
    ) {

        return new Vec3(

            Math.sqrt(
                Math.max(
                    0,
                    color.x()
                )
            ),

            Math.sqrt(
                Math.max(
                    0,
                    color.y()
                )
            ),

            Math.sqrt(
                Math.max(
                    0,
                    color.z()
                )
            )
        );
    }

    // =========================================================
    // RGB
    // =========================================================

    static int toRGB(
        Vec3 color
    ) {

        int r =
            (int)
                (
                    clamp(
                        color.x()
                    )
                        *
                        255
                );

        int g =
            (int)
                (
                    clamp(
                        color.y()
                    )
                        *
                        255
                );

        int b =
            (int)
                (
                    clamp(
                        color.z()
                    )
                        *
                        255
                );

        return
            (r << 16)
                |
                (g << 8)
                |
                b;
    }

    static double clamp(
        double value
    ) {

        return Math.max(
            0,

            Math.min(
                1,
                value
            )
        );
    }

    // =========================================================
    // Swing Draw
    // =========================================================

    @Override
    protected void paintComponent(
        Graphics g
    ) {

        super.paintComponent(
            g
        );

        // 放大显示
        g.drawImage(
            frontImage,

            0,
            0,

            getWidth(),
            getHeight(),

            null
        );

        // =====================================================
        // Debug HUD
        // =====================================================

        g.setColor(
            new Color(
                0,
                0,
                0,
                150
            )
        );

        g.fillRoundRect(
            10,
            10,
            320,
            92,
            12,
            12
        );

        g.setColor(
            Color.WHITE
        );

        g.setFont(
            new Font(
                Font.MONOSPACED,
                Font.PLAIN,
                14
            )
        );

        g.drawString(
            String.format(
                "FPS: %.1f",
                fps
            ),
            20,
            32
        );

        g.drawString(
            String.format(
                "Primary rays/s: %.2f M",
                primaryMRaysPerSecond
            ),
            20,
            52
        );

        g.drawString(
            "WASD move | Space/Shift up/down",
            20,
            72
        );

        g.drawString(
            "Hold Right Mouse + drag to look",
            20,
            92
        );
    }

    @Override
    public void removeNotify() {

        running = false;

        super.removeNotify();
    }

    // =========================================================
    // Main
    // =========================================================

    public static void main(
        String[] args
    ) {

        SwingUtilities.invokeLater(
            () -> {

                JFrame frame =
                    new JFrame(
                        "Pure CPU Interactive Ray Tracer"
                    );

                InteractiveCpuRayTracer panel =
                    new InteractiveCpuRayTracer();

                frame.setDefaultCloseOperation(
                    JFrame.EXIT_ON_CLOSE
                );

                frame.setContentPane(
                    panel
                );

                frame.pack();

                frame.setLocationRelativeTo(
                    null
                );

                frame.setVisible(
                    true
                );

                panel.requestFocusInWindow();
            }
        );
    }
}
