package dev.scx.physics;

import java.awt.*;

public interface Scene {

    /// 固定回调
    default void update(Input input) {

    }

    /// 渲染回调 (暂定 60 FPS)
    default void render(Graphics g) {

    }

}
