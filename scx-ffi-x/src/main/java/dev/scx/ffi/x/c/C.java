package dev.scx.ffi.x.c;

import dev.scx.ffi.ScxFFI;

/// 提供一些 C 标准的接口
///
/// @author scx567888
/// @version 0.0.1
public interface C {

    C C = ScxFFI.createFFI(C.class);

    long strlen(String str);

    int abs(int x);

    double sin(double x);

    double sqrt(double x);

}
