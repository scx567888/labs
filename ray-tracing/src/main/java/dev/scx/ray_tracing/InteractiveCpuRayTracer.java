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
    final Scene scene = new Scene();
    final Set<Integer> keys =
        ConcurrentHashMap.newKeySet();

    // =========================================================
    // Scene
    // =========================================================
    volatile BufferedImage frontImage =
        new BufferedImage(
            RENDER_WIDTH,
            RENDER_HEIGHT,
            BufferedImage.TYPE_INT_RGB
        );

    // =========================================================
    // Input
    // =========================================================
    BufferedImage backImage =
        new BufferedImage(
            RENDER_WIDTH,
            RENDER_HEIGHT,
            BufferedImage.TYPE_INT_RGB
        );

    // =========================================================
    // Camera
    // =========================================================
    volatile Vector3 cameraPosition =
        new Vector3(
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
    // Vector3
    // =========================================================

    static Vector3 gammaCorrect(
        Vector3 color
    ) {

        return new Vector3(

            Math.sqrt(
                Math.max(
                    0,
                    color.x
                )
            ),

            Math.sqrt(
                Math.max(
                    0,
                    color.y
                )
            ),

            Math.sqrt(
                Math.max(
                    0,
                    color.z
                )
            )
        );
    }

    // =========================================================
    // Ray
    // =========================================================

    static int toRGB(
        Vector3 color
    ) {

        int r =
            (int)
                (
                    clamp(
                        color.x
                    )
                        *
                        255
                );

        int g =
            (int)
                (
                    clamp(
                        color.y
                    )
                        *
                        255
                );

        int b =
            (int)
                (
                    clamp(
                        color.z
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

    // =========================================================
    // Material
    // =========================================================

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
    // Hit
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

    // =========================================================
    // Scene Object
    // =========================================================

    void createScene() {

        Material red =
            new Material(
                new Vector3(
                    0.85,
                    0.12,
                    0.08
                ),
                0.20,
                0.8
            );

        Material blue =
            new Material(
                new Vector3(
                    0.08,
                    0.25,
                    0.85
                ),
                0.10,
                0.5
            );

        Material mirror =
            new Material(
                new Vector3(
                    0.85,
                    0.85,
                    0.85
                ),
                0.80,
                1.0
            );

        Material ground =
            new Material(
                new Vector3(
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
                    new Vector3(
                        0,
                        0,
                        -4
                    ),
                    1.0,
                    red
                ),

                // 左边蓝球
                new Sphere(
                    new Vector3(
                        -2.0,
                        -0.25,
                        -5.0
                    ),
                    0.75,
                    blue
                ),

                // 右边镜面球
                new Sphere(
                    new Vector3(
                        2.0,
                        -0.15,
                        -5.5
                    ),
                    0.85,
                    mirror
                ),

                // 地面
                new Plane(
                    new Vector3(
                        0,
                        -1,
                        0
                    ),

                    new Vector3(
                        0,
                        1,
                        0
                    ),

                    ground
                )
            );

        scene.light =
            new PointLight(

                new Vector3(
                    -3,
                    5,
                    1
                ),

                new Vector3(
                    1.0,
                    0.95,
                    0.85
                ),

                45.0
            );
    }

    // =========================================================
    // Sphere
    // =========================================================

    Vector3 trace(
        Ray ray,
        int depth
    ) {

        if (
            depth >= MAX_BOUNCES
        ) {
            return new Vector3(
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
        Vector3 result =
            material
                .color()
                .mul(
                    0.04
                );

        // =====================================================
        // Point Light
        // =====================================================

        Vector3 toLight =
            scene
                .light
                .position()
                .sub(
                    hit.position()
                );

        double lightDistance =
            toLight.length();

        Vector3 lightDirection =
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

            Vector3 diffuseColor =
                material
                    .color()
                    .scaled(
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

            Vector3 viewDirection =
                ray
                    .direction()
                    .negated();

            Vector3 reflectedLight =
                Vector3Math.reflect(

                    lightDirection.negated(),

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

            Vector3 specular =
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

            Vector3 reflectionDirection =
                Vector3Math.reflect(

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
            Vector3 reflection =
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
    // Plane
    // =========================================================

    Vector3 sky(
        Ray ray
    ) {

        double t =
            0.5
                *
                (
                    ray.direction().y
                        +
                        1.0
                );

        Vector3 bottom =
            new Vector3(
                0.95,
                0.97,
                1.0
            );

        Vector3 top =
            new Vector3(
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
    // Light
    // =========================================================

    CameraBasis cameraBasis() {

        double cosPitch =
            Math.cos(
                pitch
            );

        Vector3 forward =
            new Vector3(

                Math.sin(yaw)
                    *
                    cosPitch,

                Math.sin(pitch),

                -Math.cos(yaw)
                    *
                    cosPitch

            ).normalized();

        Vector3 worldUp =
            new Vector3(
                0,
                1,
                0
            );

        Vector3 right =
            forward
                .cross(
                    worldUp
                )
                .normalized();

        Vector3 up =
            right
                .cross(
                    forward
                )
                .normalized();

        return new CameraBasis(
            forward,
            right,
            up
        );
    }

    // =========================================================
    // Scene
    // =========================================================

    Ray cameraRay(
        double pixelX,
        double pixelY,
        Vector3 origin,
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

        Vector3 direction =
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
    // Scene Setup
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
        Vector3 camera =
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

                    Vector3 color =
                        new Vector3(
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
    // Ray Tracing
    // =========================================================

    void updateCamera(
        double deltaTime
    ) {

        CameraBasis basis =
            cameraBasis();

        Vector3 movement =
            new Vector3(
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
                    new Vector3(
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
                    new Vector3(
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
                    .normalized()
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
    // Sky
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
    // Camera
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

                    if (!rotating) {
                        return;
                    }

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
    // Render Frame
    // =========================================================

    interface SceneObject {

        Hit intersect(
            Ray ray
        );
    }

    // =========================================================
    // Main Render Loop
    // =========================================================

    record Ray(
        Vector3 origin,
        Vector3 direction
    ) {

        Ray {

            direction =
                direction.normalized();
        }

        Vector3 at(double t) {

            return origin.add(
                direction.mul(t)
            );
        }
    }

    // =========================================================
    // Input
    // =========================================================

    record Material(
        Vector3 color,

        // 0 ~ 1
        double reflectivity,

        // 镜面高光
        double specular
    ) {
    }

    // =========================================================
    // Gamma
    // =========================================================

    record Hit(
        double distance,
        Vector3 position,
        Vector3 normal,
        Material material
    ) {
    }

    // =========================================================
    // RGB
    // =========================================================

    record Sphere(
        Vector3 center,
        double radius,
        Material material
    ) implements SceneObject {

        @Override
        public Hit intersect(
            Ray ray
        ) {

            Vector3 oc =
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

            if (discriminant < 0) {
                return null;
            }

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

                if (t <= EPSILON) {
                    return null;
                }
            }

            Vector3 position =
                ray.at(t);

            Vector3 normal =
                position
                    .sub(center)
                    .normalized();

            return new Hit(
                t,
                position,
                normal,
                material
            );
        }
    }

    record Plane(
        Vector3 point,
        Vector3 normal,
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

            if (t <= EPSILON) {
                return null;
            }

            // 确保 normal 朝向 ray
            Vector3 n =
                denominator < 0
                    ?
                    normal
                    :
                    normal.negated();

            return new Hit(
                t,
                ray.at(t),
                n,
                material
            );
        }
    }

    // =========================================================
    // Swing Draw
    // =========================================================

    record PointLight(
        Vector3 position,
        Vector3 color,
        double intensity
    ) {
    }

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
    // Main
    // =========================================================

    record CameraBasis(
        Vector3 forward,
        Vector3 right,
        Vector3 up
    ) {
    }
}
