package dev.scx.physics;

import java.awt.*;

public interface Scene {

    /// 初始化场景, 只执行一次
    default void start(SceneContext context) {

    }

    /// 固定回调
    default void update(SceneContext context) {

    }

    /// 渲染回调 (暂定 60 FPS)
    default void render(Graphics g) {

    }

}
