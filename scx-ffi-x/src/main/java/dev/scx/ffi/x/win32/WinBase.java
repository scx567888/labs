package dev.scx.ffi.x.win32;

import dev.scx.ffi.type.FFIStruct;

public final class WinBase {

    public static final int STD_OUTPUT_HANDLE = -11;

    public static class FILETIME implements FFIStruct {

        public int dwLowDateTime;
        public int dwHighDateTime;

    }

}
